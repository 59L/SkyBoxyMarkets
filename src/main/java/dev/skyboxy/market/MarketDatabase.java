package dev.skyboxy.market;

import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** SQLite DB: permanent booth locations, idempotent reset/claim ledger and metadata. */
final class MarketDatabase implements AutoCloseable {
    private final Connection conn;
    private final Path databasePath;
    MarketDatabase(File file) throws Exception {
        Class.forName("org.sqlite.JDBC");
        databasePath=file.toPath().toAbsolutePath().normalize();
        File parent=databasePath.getParent().toFile();if(!parent.exists()&&!parent.mkdirs())throw new SQLException("Could not create database directory: "+parent);
        conn=DriverManager.getConnection("jdbc:sqlite:"+databasePath);
        try(Statement s=conn.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA foreign_keys=ON");
            s.execute("""
                CREATE TABLE IF NOT EXISTS booths(
                  id TEXT PRIMARY KEY, world TEXT NOT NULL, style TEXT NOT NULL,
                  x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,
                  width INTEGER NOT NULL,height INTEGER NOT NULL,length INTEGER NOT NULL,rotation INTEGER NOT NULL,
                  owner TEXT,lease TEXT,expiry INTEGER NOT NULL DEFAULT 0,
                  access TEXT NOT NULL DEFAULT 'PUBLIC',color TEXT NOT NULL DEFAULT 'GRAY',
                  wall_changed INTEGER NOT NULL DEFAULT 0,suspended INTEGER NOT NULL DEFAULT 0,
                  resetting INTEGER NOT NULL DEFAULT 0,
                  visits INTEGER NOT NULL DEFAULT 0,unique_visitors INTEGER NOT NULL DEFAULT 0,sales REAL NOT NULL DEFAULT 0
                )""");
            s.execute("CREATE TABLE IF NOT EXISTS access(booth TEXT NOT NULL,player TEXT NOT NULL,kind TEXT NOT NULL,PRIMARY KEY(booth,player,kind))");
            s.execute("CREATE TABLE IF NOT EXISTS placed(booth TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,material TEXT NOT NULL,owner TEXT,item BLOB,PRIMARY KEY(booth,x,y,z))");
            s.execute("CREATE TABLE IF NOT EXISTS visits(booth TEXT NOT NULL,player TEXT NOT NULL,last_visit INTEGER NOT NULL,PRIMARY KEY(booth,player))");
            s.execute("CREATE TABLE IF NOT EXISTS cooldowns(player TEXT NOT NULL,style TEXT NOT NULL,until_ms INTEGER NOT NULL,PRIMARY KEY(player,style))");
            s.execute("""
                CREATE TABLE IF NOT EXISTS recovery(
                  id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, booth TEXT NOT NULL,
                  lease TEXT NOT NULL, source TEXT NOT NULL, slot INTEGER NOT NULL,
                  item BLOB NOT NULL,claimed INTEGER NOT NULL DEFAULT 0,
                  UNIQUE(lease,source,slot))""");
            s.execute("CREATE TABLE IF NOT EXISTS resets(booth TEXT PRIMARY KEY,lease TEXT NOT NULL,owner TEXT NOT NULL,phase TEXT NOT NULL,started INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT,at INTEGER NOT NULL,actor TEXT NOT NULL,action TEXT NOT NULL,detail TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY,applied_at INTEGER NOT NULL)");
            s.execute("""
                CREATE TABLE IF NOT EXISTS payment_intents(
                  id TEXT PRIMARY KEY,player TEXT NOT NULL,booth TEXT NOT NULL,action TEXT NOT NULL,
                  amount REAL NOT NULL,provider TEXT NOT NULL,status TEXT NOT NULL,detail TEXT NOT NULL DEFAULT '',
                  created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)
                """);
            s.execute("CREATE INDEX IF NOT EXISTS payment_intents_status_created ON payment_intents(status,created_at)");
            s.execute("CREATE TABLE IF NOT EXISTS shop_sales(provider TEXT NOT NULL,sale_key TEXT NOT NULL,booth TEXT NOT NULL,amount REAL NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(provider,sale_key))");
        }
        ensureColumn("placed","item","BLOB");
        ensureColumn("recovery","claim_state","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("recovery","claim_owner","TEXT");
        ensureColumn("recovery","claim_started","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("resets","restore_cursor","INTEGER NOT NULL DEFAULT 0");
        try(Statement s=conn.createStatement()){
            // Upgrade the old boolean ledger without reopening already delivered entries.
            s.execute("CREATE INDEX IF NOT EXISTS recovery_owner_state_id ON recovery(owner,claim_state,id)");
            s.executeUpdate("UPDATE recovery SET claim_state=2 WHERE claimed=1 AND claim_state=0");
            s.executeUpdate("INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(1,"+System.currentTimeMillis()+")");
            s.executeUpdate("INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(2,"+System.currentTimeMillis()+")");
            s.executeUpdate("INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(3,"+System.currentTimeMillis()+")");
            s.executeUpdate("INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(4,"+System.currentTimeMillis()+")");
            s.executeUpdate("INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(5,"+System.currentTimeMillis()+")");
        }
    }
    /** Adds a shop sale to the booth once per (provider,key); returns false for a replay. */
    synchronized boolean recordShopSale(Booth b,String provider,String key,double amount) throws SQLException {
        double oldSales=b.sales;boolean auto=conn.getAutoCommit();conn.setAutoCommit(false);
        try{
            try(PreparedStatement p=conn.prepareStatement("INSERT OR IGNORE INTO shop_sales(provider,sale_key,booth,amount,at) VALUES(?,?,?,?,?)")){
                p.setString(1,provider);p.setString(2,key);p.setString(3,b.id);p.setDouble(4,amount);p.setLong(5,System.currentTimeMillis());
                if(p.executeUpdate()==0){conn.rollback();return false;}
            }
            b.sales+=amount;save(b);conn.commit();return true;
        }catch(Exception e){conn.rollback();b.sales=oldSales;if(e instanceof SQLException sql)throw sql;throw new SQLException("Shop sale transaction failed",e);}
        finally{conn.setAutoCommit(auto);}
    }
    private void ensureColumn(String table,String column,String declaration)throws SQLException {
        boolean exists=false;
        try(Statement s=conn.createStatement();ResultSet rs=s.executeQuery("PRAGMA table_info("+table+")")){
            while(rs.next())if(column.equalsIgnoreCase(rs.getString("name"))){exists=true;break;}
        }
        if(!exists)try(Statement s=conn.createStatement()){s.execute("ALTER TABLE "+table+" ADD COLUMN "+column+" "+declaration);}
    }
    private static String id(UUID u){return u==null?null:u.toString();}
    enum PaymentResolution { REFUND_VERIFIED, NO_CHARGE_VERIFIED, ACTION_VERIFIED }
    record PaymentIntent(String id,UUID player,String booth,String action,double amount,String provider,
                         String status,String detail,long createdAt,long updatedAt) {}

    synchronized String createPaymentIntent(UUID player,String booth,String action,double amount,String provider) throws SQLException {
        if(player==null||booth==null||booth.isBlank()||action==null||action.isBlank()||!Double.isFinite(amount)||amount<0)
            throw new SQLException("Invalid payment intent details");
        String key=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        try(PreparedStatement statement=conn.prepareStatement("INSERT INTO payment_intents(id,player,booth,action,amount,provider,status,detail,created_at,updated_at) VALUES(?,?,?,?,?,?, 'PREPARED','',?,?)")){
            statement.setString(1,key);statement.setString(2,id(player));statement.setString(3,booth);statement.setString(4,action);
            statement.setDouble(5,amount);statement.setString(6,provider==null||provider.isBlank()?"Unavailable":provider);
            statement.setLong(7,now);statement.setLong(8,now);statement.executeUpdate();
        }
        return key;
    }
    synchronized void transitionPaymentIntent(String key,String expected,String next,String detail) throws SQLException {
        if(!validPaymentTransition(expected,next))throw new SQLException("Invalid payment intent transition "+expected+" -> "+next);
        try(PreparedStatement statement=conn.prepareStatement("UPDATE payment_intents SET status=?,detail=?,updated_at=? WHERE id=? AND status=?")){
            statement.setString(1,next);statement.setString(2,detail==null?"":detail);statement.setLong(3,System.currentTimeMillis());
            statement.setString(4,key);statement.setString(5,expected);
            if(statement.executeUpdate()!=1)throw new SQLException("Payment intent is missing or no longer in state "+expected+": "+key);
        }
    }
    private static boolean validPaymentTransition(String expected,String next){
        return switch(expected){
            case "PREPARED" -> Set.of("CHARGED","DECLINED","REVIEW_REQUIRED").contains(next);
            case "CHARGED" -> Set.of("APPLIED","REFUND_PENDING","REVIEW_REQUIRED").contains(next);
            case "REFUND_PENDING" -> Set.of("REFUNDED","REVIEW_REQUIRED").contains(next);
            case "REVIEW_REQUIRED" -> Set.of("REFUND_VERIFIED","NO_CHARGE_VERIFIED","ACTION_VERIFIED").contains(next);
            default -> false;
        };
    }
    synchronized int reviewInterruptedPaymentIntents() throws SQLException {
        try(PreparedStatement statement=conn.prepareStatement("UPDATE payment_intents SET status='REVIEW_REQUIRED',detail='Interrupted while state was '||status,updated_at=? WHERE status IN ('PREPARED','CHARGED','REFUND_PENDING')")){
            statement.setLong(1,System.currentTimeMillis());return statement.executeUpdate();
        }
    }
    synchronized int unresolvedPaymentIntentCount() throws SQLException {
        try(Statement statement=conn.createStatement();ResultSet results=statement.executeQuery("SELECT COUNT(*) FROM payment_intents WHERE status IN ('PREPARED','CHARGED','REFUND_PENDING','REVIEW_REQUIRED')")){
            return results.next()?results.getInt(1):0;
        }
    }
    synchronized List<PaymentIntent> paymentIntents(int page,int pageSize) throws SQLException {
        if(page<0||pageSize<1||pageSize>100)throw new SQLException("Invalid payment-intent page");
        ArrayList<PaymentIntent> intents=new ArrayList<>();
        try(PreparedStatement statement=conn.prepareStatement("SELECT * FROM payment_intents WHERE status IN ('PREPARED','CHARGED','REFUND_PENDING','REVIEW_REQUIRED') ORDER BY created_at,id LIMIT ? OFFSET ?")){
            statement.setInt(1,pageSize);statement.setInt(2,page*pageSize);
            try(ResultSet results=statement.executeQuery()){while(results.next())intents.add(readPaymentIntent(results));}
        }
        return intents;
    }
    synchronized PaymentIntent paymentIntent(String key) throws SQLException {
        try(PreparedStatement statement=conn.prepareStatement("SELECT * FROM payment_intents WHERE id=?")){
            statement.setString(1,key);try(ResultSet results=statement.executeQuery()){return results.next()?readPaymentIntent(results):null;}
        }
    }
    synchronized PaymentIntent latestPaymentIntent(UUID player,String booth,String action) throws SQLException {
        try(PreparedStatement statement=conn.prepareStatement("SELECT * FROM payment_intents WHERE player=? AND booth=? AND action=? ORDER BY created_at DESC,rowid DESC LIMIT 1")){
            statement.setString(1,id(player));statement.setString(2,booth);statement.setString(3,action);
            try(ResultSet results=statement.executeQuery()){return results.next()?readPaymentIntent(results):null;}
        }
    }
    private static PaymentIntent readPaymentIntent(ResultSet results) throws SQLException {
        return new PaymentIntent(results.getString("id"),UUID.fromString(results.getString("player")),results.getString("booth"),
                results.getString("action"),results.getDouble("amount"),results.getString("provider"),results.getString("status"),
                results.getString("detail"),results.getLong("created_at"),results.getLong("updated_at"));
    }
    synchronized void resolvePaymentIntent(String key,PaymentResolution resolution,UUID staff) throws SQLException {
        if(resolution==null||staff==null)throw new SQLException("Payment resolution needs a staff actor and outcome");
        String next=resolution.name();boolean auto=conn.getAutoCommit();conn.setAutoCommit(false);
        try{
            transitionPaymentIntent(key,"REVIEW_REQUIRED",next,"Verified by staff "+staff);
            try(PreparedStatement audit=conn.prepareStatement("INSERT INTO audit(at,actor,action,detail) VALUES(?,?,?,?)")){
                audit.setLong(1,System.currentTimeMillis());audit.setString(2,id(staff));audit.setString(3,"payment-review");audit.setString(4,key+"="+next);audit.executeUpdate();
            }
            conn.commit();
        }catch(Exception exception){conn.rollback();if(exception instanceof SQLException sql)throw sql;throw new SQLException("Payment intent resolution failed",exception);}
        finally{conn.setAutoCommit(auto);}
    }
    private void transitionPaymentInTransaction(String key,String expected,String next,String detail) throws SQLException {
        if(!validPaymentTransition(expected,next))throw new SQLException("Invalid payment intent transition "+expected+" -> "+next);
        try(PreparedStatement statement=conn.prepareStatement("UPDATE payment_intents SET status=?,detail=?,updated_at=? WHERE id=? AND status=?")){
            statement.setString(1,next);statement.setString(2,detail==null?"":detail);statement.setLong(3,System.currentTimeMillis());
            statement.setString(4,key);statement.setString(5,expected);
            if(statement.executeUpdate()!=1)throw new SQLException("Payment intent is missing or no longer in state "+expected+": "+key);
        }
    }
    List<Booth> loadBooths() throws SQLException {
        ArrayList<Booth> result=new ArrayList<>();
        try(Statement s=conn.createStatement();ResultSet rs=s.executeQuery("SELECT * FROM booths")){
            while(rs.next()){
                Booth b=new Booth(rs.getString("id"),rs.getString("world"),rs.getString("style"),rs.getInt("x"),rs.getInt("y"),rs.getInt("z"),rs.getInt("width"),rs.getInt("height"),rs.getInt("length"),rs.getInt("rotation"));
                if(rs.getString("owner")!=null)b.owner=UUID.fromString(rs.getString("owner"));
                b.leaseId=rs.getString("lease");b.expiry=rs.getLong("expiry");b.access=rs.getString("access");b.wallColor=rs.getString("color");
                b.wallChanged=rs.getLong("wall_changed");b.suspended=rs.getBoolean("suspended");b.resetting=rs.getBoolean("resetting");
                b.visits=rs.getLong("visits");b.uniqueVisitors=rs.getLong("unique_visitors");b.sales=rs.getDouble("sales");
                result.add(b);
            }
        }
        try(Statement s=conn.createStatement();ResultSet rs=s.executeQuery("SELECT * FROM access")){
            Map<String,Booth> lookup=new HashMap<>();result.forEach(b->lookup.put(b.id,b));
            while(rs.next()){
                Booth b=lookup.get(rs.getString("booth"));if(b==null)continue;
                UUID u=UUID.fromString(rs.getString("player"));
                if("TRUST".equals(rs.getString("kind"))) b.trusted.add(u);else b.banned.add(u);
            }
        }
        return result;
    }
    synchronized void save(Booth b) throws SQLException {
        try(PreparedStatement p=conn.prepareStatement("""
            INSERT INTO booths(id,world,style,x,y,z,width,height,length,rotation,owner,lease,expiry,access,color,wall_changed,suspended,resetting,visits,unique_visitors,sales)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET
            owner=excluded.owner,lease=excluded.lease,expiry=excluded.expiry,access=excluded.access,color=excluded.color,
            wall_changed=excluded.wall_changed,suspended=excluded.suspended,resetting=excluded.resetting,
            visits=excluded.visits,unique_visitors=excluded.unique_visitors,sales=excluded.sales""")){
            int i=1;p.setString(i++,b.id);p.setString(i++,b.world);p.setString(i++,b.style);
            p.setInt(i++,b.x);p.setInt(i++,b.y);p.setInt(i++,b.z);p.setInt(i++,b.width);p.setInt(i++,b.height);p.setInt(i++,b.length);p.setInt(i++,b.rotation);
            p.setString(i++,id(b.owner));p.setString(i++,b.leaseId);p.setLong(i++,b.expiry);
            p.setString(i++,b.access);p.setString(i++,b.wallColor);p.setLong(i++,b.wallChanged);
            p.setBoolean(i++,b.suspended);p.setBoolean(i++,b.resetting);p.setLong(i++,b.visits);p.setLong(i++,b.uniqueVisitors);p.setDouble(i,b.sales);p.executeUpdate();
        }
    }
    synchronized void access(Booth b,UUID player,boolean trust,boolean enable) throws SQLException {
        String kind=trust?"TRUST":"BAN";
        try(PreparedStatement p=conn.prepareStatement(enable?"INSERT OR IGNORE INTO access(booth,player,kind) VALUES(?,?,?)":"DELETE FROM access WHERE booth=? AND player=? AND kind=?")){
            p.setString(1,b.id);p.setString(2,id(player));p.setString(3,kind);p.executeUpdate();
        }
        if(trust){if(enable)b.trusted.add(player);else b.trusted.remove(player);}
        else {if(enable)b.banned.add(player);else b.banned.remove(player);}
    }
    synchronized void clearAccess(Booth b) throws SQLException {
        try(PreparedStatement p=conn.prepareStatement("DELETE FROM access WHERE booth=?")){p.setString(1,b.id);p.executeUpdate();}
        b.trusted.clear();b.banned.clear();
    }
    synchronized void place(Booth b,int x,int y,int z,Material mat,UUID actor) throws SQLException {
        place(b,x,y,z,mat,actor,null);
    }
    synchronized void place(Booth b,int x,int y,int z,Material mat,UUID actor,ItemStack item) throws SQLException {
        try(PreparedStatement p=conn.prepareStatement("INSERT INTO placed(booth,x,y,z,material,owner,item) VALUES(?,?,?,?,?,?,?) ON CONFLICT(booth,x,y,z) DO UPDATE SET material=excluded.material,owner=excluded.owner,item=excluded.item")){
            p.setString(1,b.id);p.setInt(2,x);p.setInt(3,y);p.setInt(4,z);p.setString(5,mat.name());p.setString(6,id(actor));p.setBytes(7,item==null?null:item.serializeAsBytes());p.executeUpdate();
        }
    }
    synchronized boolean wasPlaced(Booth b,int x,int y,int z) throws SQLException {
        try(PreparedStatement p=conn.prepareStatement("SELECT 1 FROM placed WHERE booth=? AND x=? AND y=? AND z=?")){
            p.setString(1,b.id);p.setInt(2,x);p.setInt(3,y);p.setInt(4,z);
            try(ResultSet rs=p.executeQuery()){return rs.next();}
        }
    }
    synchronized void breakPlaced(Booth b,int x,int y,int z) throws SQLException {
        try(PreparedStatement p=conn.prepareStatement("DELETE FROM placed WHERE booth=? AND x=? AND y=? AND z=?")){
            p.setString(1,b.id);p.setInt(2,x);p.setInt(3,y);p.setInt(4,z);p.executeUpdate();
        }
    }
    record Placed(int x,int y,int z, Material type, ItemStack item) {}
    synchronized List<Placed> placed(Booth b) throws SQLException {
        ArrayList<Placed> out=new ArrayList<>();
        try(PreparedStatement p=conn.prepareStatement("SELECT x,y,z,material,item FROM placed WHERE booth=?")){
            p.setString(1,b.id);try(ResultSet rs=p.executeQuery()){
                while(rs.next()){
                    Material mat=Material.matchMaterial(rs.getString(4));if(mat==null)continue;
                    byte[] data=rs.getBytes(5);ItemStack stack=data==null?null:ItemStack.deserializeBytes(data);
                    out.add(new Placed(rs.getInt(1),rs.getInt(2),rs.getInt(3),mat,stack));
                }
            }
        }
        return out;
    }
    synchronized void clearPlaced(Booth b) throws SQLException {
        try(PreparedStatement p=conn.prepareStatement("DELETE FROM placed WHERE booth=?")){p.setString(1,b.id);p.executeUpdate();}
    }
    synchronized boolean visit(Booth b,UUID u,long now,long cooldownMs) throws SQLException {
        long last=0;boolean existed=false;
        try(PreparedStatement p=conn.prepareStatement("SELECT last_visit FROM visits WHERE booth=? AND player=?")){
            p.setString(1,b.id);p.setString(2,id(u));try(ResultSet rs=p.executeQuery()){if(rs.next()){last=rs.getLong(1);existed=true;}}
        }
        if(now-last<cooldownMs)return false;
        try(PreparedStatement p=conn.prepareStatement("INSERT INTO visits(booth,player,last_visit) VALUES(?,?,?) ON CONFLICT(booth,player) DO UPDATE SET last_visit=excluded.last_visit")){
            p.setString(1,b.id);p.setString(2,id(u));p.setLong(3,now);p.executeUpdate();
        }
        b.visits++;if(!existed)b.uniqueVisitors++;
        save(b);return true;
    }
    synchronized long cooldown(UUID u,String style) throws SQLException {
        try(PreparedStatement p=conn.prepareStatement("SELECT until_ms FROM cooldowns WHERE player=? AND style=?")){
            p.setString(1,id(u));p.setString(2,style);try(ResultSet rs=p.executeQuery()){return rs.next()?rs.getLong(1):0;}
        }
    }
    synchronized void cooldown(UUID u,String style,long until) throws SQLException {
        try(PreparedStatement p=conn.prepareStatement("INSERT INTO cooldowns(player,style,until_ms) VALUES(?,?,?) ON CONFLICT(player,style) DO UPDATE SET until_ms=excluded.until_ms")){
            p.setString(1,id(u));p.setString(2,style);p.setLong(3,until);p.executeUpdate();
        }
    }
    synchronized void commitRental(Booth b,UUID owner,String lease,long expiry,long cooldownUntil) throws SQLException {
        commitRental(b,owner,lease,expiry,cooldownUntil,null);
    }
    synchronized void commitRental(Booth b,UUID owner,String lease,long expiry,long cooldownUntil,String paymentIntent) throws SQLException {
        UUID oldOwner=b.owner;String oldLease=b.leaseId;long oldExpiry=b.expiry;String oldAccess=b.access,oldColor=b.wallColor;
        long oldWallChanged=b.wallChanged;boolean oldSuspended=b.suspended,oldResetting=b.resetting;
        boolean auto=conn.getAutoCommit();conn.setAutoCommit(false);
        try{
            b.owner=owner;b.leaseId=lease;b.expiry=expiry;b.access="PUBLIC";b.wallColor="GRAY";b.wallChanged=0;
            save(b);cooldown(owner,b.style,cooldownUntil);
            try(PreparedStatement p=conn.prepareStatement("INSERT INTO audit(at,actor,action,detail) VALUES(?,?,?,?)")){
                p.setLong(1,System.currentTimeMillis());p.setString(2,id(owner));p.setString(3,"rent");p.setString(4,b.id);p.executeUpdate();
            }
            if(paymentIntent!=null)transitionPaymentInTransaction(paymentIntent,"CHARGED","APPLIED","Rental committed: "+b.id);
            conn.commit();
        }catch(Exception e){
            conn.rollback();b.owner=oldOwner;b.leaseId=oldLease;b.expiry=oldExpiry;b.access=oldAccess;b.wallColor=oldColor;
            b.wallChanged=oldWallChanged;b.suspended=oldSuspended;b.resetting=oldResetting;
            if(e instanceof SQLException sql)throw sql;throw new SQLException("Rental transaction failed",e);
        }finally{conn.setAutoCommit(auto);}
    }
    synchronized void commitRenewal(Booth b,long expiry,UUID actor) throws SQLException {
        commitRenewal(b,expiry,actor,null);
    }
    synchronized void commitRenewal(Booth b,long expiry,UUID actor,String paymentIntent) throws SQLException {
        long oldExpiry=b.expiry;boolean auto=conn.getAutoCommit();conn.setAutoCommit(false);
        try{
            b.expiry=expiry;save(b);
            try(PreparedStatement p=conn.prepareStatement("INSERT INTO audit(at,actor,action,detail) VALUES(?,?,?,?)")){
                p.setLong(1,System.currentTimeMillis());p.setString(2,id(actor));p.setString(3,"renew");p.setString(4,b.id);p.executeUpdate();
            }
            if(paymentIntent!=null)transitionPaymentInTransaction(paymentIntent,"CHARGED","APPLIED","Renewal committed: "+b.id);
            conn.commit();
        }catch(Exception e){conn.rollback();b.expiry=oldExpiry;if(e instanceof SQLException sql)throw sql;throw new SQLException("Renewal transaction failed",e);}
        finally{conn.setAutoCommit(auto);}
    }
    synchronized void commitWallChange(Booth b,String color,long changedAt,UUID actor,String paymentIntent) throws SQLException {
        String oldColor=b.wallColor;long oldChanged=b.wallChanged;boolean auto=conn.getAutoCommit();conn.setAutoCommit(false);
        try{
            b.wallColor=color;b.wallChanged=changedAt;save(b);
            try(PreparedStatement audit=conn.prepareStatement("INSERT INTO audit(at,actor,action,detail) VALUES(?,?,?,?)")){
                audit.setLong(1,System.currentTimeMillis());audit.setString(2,id(actor));audit.setString(3,"walls");audit.setString(4,b.id+" "+color);audit.executeUpdate();
            }
            transitionPaymentInTransaction(paymentIntent,"CHARGED","APPLIED","Wall change committed: "+b.id+" "+color);
            conn.commit();
        }catch(Exception exception){conn.rollback();b.wallColor=oldColor;b.wallChanged=oldChanged;
            if(exception instanceof SQLException sql)throw sql;throw new SQLException("Wall-change transaction failed",exception);
        }finally{conn.setAutoCommit(auto);}
    }
    synchronized void beginReset(Booth b) throws SQLException {
        if(b.owner==null||b.leaseId==null)throw new SQLException("No lease to reset");
        try(PreparedStatement p=conn.prepareStatement("INSERT OR IGNORE INTO resets(booth,lease,owner,phase,started) VALUES(?,?,?,?,?)")){
            p.setString(1,b.id);p.setString(2,b.leaseId);p.setString(3,id(b.owner));p.setString(4,"SNAPSHOTTING");p.setLong(5,System.currentTimeMillis());p.executeUpdate();
        }
        b.resetting=true;save(b);
    }
    record Reset(String booth,String lease,UUID owner,String phase,int restoreCursor) {}
    synchronized List<Reset> resets() throws SQLException {
        ArrayList<Reset> list=new ArrayList<>();
        try(Statement p=conn.createStatement();ResultSet rs=p.executeQuery("SELECT * FROM resets")){
            while(rs.next())list.add(new Reset(rs.getString("booth"),rs.getString("lease"),UUID.fromString(rs.getString("owner")),rs.getString("phase"),rs.getInt("restore_cursor")));
        }
        return list;
    }
    synchronized void resetPhase(Booth b,String phase)throws SQLException {
        try(PreparedStatement p=conn.prepareStatement("UPDATE resets SET phase=?,restore_cursor=CASE WHEN ?='RESTORING' THEN 0 ELSE restore_cursor END WHERE booth=?")){
            p.setString(1,phase);p.setString(2,phase);p.setString(3,b.id);
            if(p.executeUpdate()!=1)throw new SQLException("Reset journal missing for booth "+b.id);
        }
    }
    synchronized void resetCursor(Booth b,int cursor)throws SQLException {
        if(cursor<0)throw new SQLException("Reset cursor cannot be negative");
        try(PreparedStatement p=conn.prepareStatement("UPDATE resets SET restore_cursor=? WHERE booth=? AND phase='RESTORING' AND restore_cursor<=?")){
            p.setInt(1,cursor);p.setString(2,b.id);p.setInt(3,cursor);
            if(p.executeUpdate()!=1)throw new SQLException("Reset journal is not restoring booth "+b.id);
        }
    }
    synchronized void finishReset(Booth b) throws SQLException {
        // Database changes commit atomically. Runtime lease state is saved on same connection.
        UUID oldOwner=b.owner;String oldLease=b.leaseId,oldAccess=b.access,oldColor=b.wallColor;
        long oldExpiry=b.expiry,oldWallChanged=b.wallChanged,oldVisits=b.visits,oldUniqueVisitors=b.uniqueVisitors;
        double oldSales=b.sales;boolean oldSuspended=b.suspended,oldResetting=b.resetting;
        Set<UUID> oldTrusted=new HashSet<>(b.trusted),oldBanned=new HashSet<>(b.banned);
        boolean auto=conn.getAutoCommit();conn.setAutoCommit(false);
        try {
            clearPlaced(b);clearAccess(b);
            try(PreparedStatement p=conn.prepareStatement("DELETE FROM visits WHERE booth=?")){p.setString(1,b.id);p.executeUpdate();}
            try(PreparedStatement p=conn.prepareStatement("DELETE FROM resets WHERE booth=?")){p.setString(1,b.id);p.executeUpdate();}
            b.owner=null;b.leaseId=null;b.expiry=0;b.access="PUBLIC";b.wallColor="GRAY";b.wallChanged=0;b.resetting=false;b.visits=0;b.uniqueVisitors=0;b.sales=0;
            save(b);conn.commit();
        }catch(Exception e){
            conn.rollback();b.owner=oldOwner;b.leaseId=oldLease;b.expiry=oldExpiry;b.access=oldAccess;b.wallColor=oldColor;
            b.wallChanged=oldWallChanged;b.suspended=oldSuspended;b.resetting=oldResetting;b.visits=oldVisits;
            b.uniqueVisitors=oldUniqueVisitors;b.sales=oldSales;b.trusted.clear();b.trusted.addAll(oldTrusted);
            b.banned.clear();b.banned.addAll(oldBanned);throw e;
        }finally{conn.setAutoCommit(auto);}
    }
    synchronized void recovery(UUID owner,Booth b,String lease,String source,int slot,ItemStack item) throws SQLException {
        if(item==null||item.getType().isAir()||item.getAmount()<=0)return;
        storeRecovery(owner,b.id,lease,source,slot,item.serializeAsBytes());
    }
    record RecoveryItem(String source,int slot,ItemStack item) {}
    synchronized void recoveryBatch(UUID owner,Booth b,String lease,List<RecoveryItem> items) throws SQLException {
        if(items.isEmpty())return;
        boolean auto=conn.getAutoCommit();conn.setAutoCommit(false);
        try(PreparedStatement p=conn.prepareStatement("INSERT OR IGNORE INTO recovery(owner,booth,lease,source,slot,item) VALUES(?,?,?,?,?,?)")){
            for(RecoveryItem entry:items){
                ItemStack item=entry.item();if(item==null||item.getType().isAir()||item.getAmount()<=0)continue;
                p.setString(1,id(owner));p.setString(2,b.id);p.setString(3,lease);p.setString(4,entry.source());
                p.setInt(5,entry.slot());p.setBytes(6,item.serializeAsBytes());p.addBatch();
            }
            p.executeBatch();conn.commit();
        }catch(Exception e){
            conn.rollback();if(e instanceof SQLException sql)throw sql;throw new SQLException("Recovery snapshot batch failed",e);
        }finally{conn.setAutoCommit(auto);}
    }
    synchronized long storeRecovery(UUID owner,String booth,String lease,String source,int slot,byte[] item) throws SQLException {
        if(item==null||item.length==0)throw new SQLException("Cannot store an empty recovery item");
        try(PreparedStatement p=conn.prepareStatement("INSERT OR IGNORE INTO recovery(owner,booth,lease,source,slot,item) VALUES(?,?,?,?,?,?)")){
            p.setString(1,id(owner));p.setString(2,booth);p.setString(3,lease);p.setString(4,source);p.setInt(5,slot);p.setBytes(6,item);p.executeUpdate();
        }
        try(PreparedStatement p=conn.prepareStatement("SELECT id FROM recovery WHERE lease=? AND source=? AND slot=?")){
            p.setString(1,lease);p.setString(2,source);p.setInt(3,slot);
            try(ResultSet rs=p.executeQuery()){if(rs.next())return rs.getLong(1);}
        }
        throw new SQLException("Recovery entry was not available after insert");
    }
    record Recovered(long id,String booth,String source,ItemStack item) {}
    synchronized List<Recovered> recovered(UUID owner,int page,int size) throws SQLException {
        List<Recovered> rows=new ArrayList<>();
        try(PreparedStatement p=conn.prepareStatement("SELECT id,booth,source,item FROM recovery WHERE owner=? AND claim_state=0 ORDER BY id LIMIT ? OFFSET ?")){
            p.setString(1,id(owner));p.setInt(2,size);p.setInt(3,Math.max(page,0)*size);
            try(ResultSet rs=p.executeQuery()){while(rs.next())rows.add(new Recovered(rs.getLong(1),rs.getString(2),rs.getString(3),ItemStack.deserializeBytes(rs.getBytes(4))));}
        }return rows;
    }
    synchronized int recoveryCount(UUID owner) throws SQLException {
        try(PreparedStatement p=conn.prepareStatement("SELECT COUNT(*) FROM recovery WHERE owner=? AND claim_state=0")){
            p.setString(1,id(owner));try(ResultSet rs=p.executeQuery()){return rs.next()?rs.getInt(1):0;}}
    }
    synchronized long recoveryUpperBound(UUID owner)throws SQLException {
        try(PreparedStatement query=conn.prepareStatement("SELECT COALESCE(MAX(id),0) FROM recovery WHERE owner=? AND claim_state=0")){
            query.setString(1,id(owner));try(ResultSet rows=query.executeQuery()){return rows.next()?rows.getLong(1):0;}
        }
    }
    synchronized List<Long> recoveryKeysAfter(UUID owner,long after,long upperBound,int limit)throws SQLException {
        if(after<0||upperBound<0||limit<1||limit>45)throw new IllegalArgumentException("Invalid bounded recovery page");
        List<Long> keys=new ArrayList<>();
        try(PreparedStatement query=conn.prepareStatement("SELECT id FROM recovery WHERE owner=? AND claim_state=0 AND id>? AND id<=? ORDER BY id LIMIT ?")){
            query.setString(1,id(owner));query.setLong(2,after);query.setLong(3,upperBound);query.setInt(4,limit);
            try(ResultSet rows=query.executeQuery()){while(rows.next())keys.add(rows.getLong(1));}
        }return keys;
    }
    synchronized boolean beginClaim(long key,UUID owner) throws SQLException {
        try(PreparedStatement p=conn.prepareStatement("UPDATE recovery SET claim_state=1,claim_owner=?,claim_started=? WHERE id=? AND owner=? AND claim_state=0")){
            p.setString(1,id(owner));p.setLong(2,System.currentTimeMillis());p.setLong(3,key);p.setString(4,id(owner));return p.executeUpdate()==1;
        }
    }
    synchronized byte[] recoveryPayload(long key,UUID owner)throws SQLException {
        try(PreparedStatement p=conn.prepareStatement("SELECT item FROM recovery WHERE id=? AND owner=? AND claim_state=0")){
            p.setLong(1,key);p.setString(2,id(owner));try(ResultSet rows=p.executeQuery()){return rows.next()?rows.getBytes(1):null;}
        }
    }
    synchronized boolean beginClaim(long key,UUID owner,byte[] expected,byte[] delivery,byte[] remainder) throws SQLException {
        if(expected==null||expected.length==0||delivery==null||delivery.length==0||(remainder!=null&&remainder.length==0))
            throw new IllegalArgumentException("Recovery claim payloads must be nonempty");
        boolean auto=conn.getAutoCommit();conn.setAutoCommit(false);
        try{
            try(PreparedStatement p=conn.prepareStatement("UPDATE recovery SET item=?,claim_state=1,claim_owner=?,claim_started=? WHERE id=? AND owner=? AND claim_state=0 AND item=?")){
                p.setBytes(1,delivery);p.setString(2,id(owner));p.setLong(3,System.currentTimeMillis());
                p.setLong(4,key);p.setString(5,id(owner));p.setBytes(6,expected);
                if(p.executeUpdate()!=1){conn.rollback();return false;}
            }
            if(remainder!=null)try(PreparedStatement p=conn.prepareStatement("INSERT INTO recovery(owner,booth,lease,source,slot,item) SELECT owner,booth,lease,?,0,? FROM recovery WHERE id=?")){
                p.setString(1,"claim-remainder:"+key);p.setBytes(2,remainder);p.setLong(3,key);
                if(p.executeUpdate()!=1)throw new SQLException("Could not preserve partial-claim remainder");
            }
            conn.commit();return true;
        }catch(SQLException ex){conn.rollback();throw ex;}
        finally{conn.setAutoCommit(auto);}
    }
    synchronized boolean completeClaim(long key,UUID owner) throws SQLException {
        try(PreparedStatement p=conn.prepareStatement("UPDATE recovery SET claim_state=2,claim_owner=NULL,claim_started=0,claimed=1 WHERE id=? AND owner=? AND claim_state=1 AND claim_owner=?")){
            p.setLong(1,key);p.setString(2,id(owner));p.setString(3,id(owner));return p.executeUpdate()==1;
        }
    }
    synchronized void releaseClaim(long key,UUID owner) throws SQLException {
        try(PreparedStatement p=conn.prepareStatement("UPDATE recovery SET claim_state=0,claim_owner=NULL,claim_started=0 WHERE id=? AND owner=? AND claim_state=1 AND claim_owner=?")){
            p.setLong(1,key);p.setString(2,id(owner));p.setString(3,id(owner));p.executeUpdate();
        }
    }
    synchronized List<Long> inFlightClaims(UUID owner) throws SQLException {
        List<Long> ids=new ArrayList<>();
        try(PreparedStatement p=conn.prepareStatement("SELECT id FROM recovery WHERE owner=? AND claim_state=1 AND claim_owner=? ORDER BY id")){
            p.setString(1,id(owner));p.setString(2,id(owner));try(ResultSet rs=p.executeQuery()){while(rs.next())ids.add(rs.getLong(1));}
        }
        return ids;
    }
    synchronized int inFlightClaimCount() throws SQLException {
        try(PreparedStatement p=conn.prepareStatement("SELECT COUNT(*) FROM recovery WHERE claim_state=1")){
            try(ResultSet rs=p.executeQuery()){return rs.next()?rs.getInt(1):0;}
        }
    }
    synchronized boolean reconcileClaim(long key,UUID owner,boolean receiptPresent) throws SQLException {
        if(receiptPresent)return completeClaim(key,owner);
        try(PreparedStatement p=conn.prepareStatement("UPDATE recovery SET claim_state=0,claim_owner=NULL,claim_started=0 WHERE id=? AND owner=? AND claim_state=1 AND claim_owner=?")){
            p.setLong(1,key);p.setString(2,id(owner));p.setString(3,id(owner));return p.executeUpdate()==1;
        }
    }
    synchronized boolean claimCompleted(long key,UUID owner) throws SQLException {
        try(PreparedStatement p=conn.prepareStatement("SELECT 1 FROM recovery WHERE id=? AND owner=? AND claim_state=2")){
            p.setLong(1,key);p.setString(2,id(owner));try(ResultSet rs=p.executeQuery()){return rs.next();}
        }
    }
    synchronized void audit(String actor,String action,String details){
        try(PreparedStatement p=conn.prepareStatement("INSERT INTO audit(at,actor,action,detail) VALUES(?,?,?,?)")){
            p.setLong(1,System.currentTimeMillis());p.setString(2,actor);p.setString(3,action);p.setString(4,details);p.executeUpdate();
        }catch(SQLException e){e.printStackTrace();}
    }
    synchronized String integrityCheck()throws SQLException {
        List<String> results=new ArrayList<>();
        try(Statement statement=conn.createStatement();ResultSet rows=statement.executeQuery("PRAGMA integrity_check")){
            while(rows.next())results.add(rows.getString(1));
        }
        return String.join("; ",results);
    }
    synchronized void backup(File destination)throws Exception {
        Path target=destination.toPath().toAbsolutePath().normalize();
        if(target.equals(databasePath))throw new SQLException("Backup destination cannot overwrite the active database");
        Path parent=target.getParent();if(parent==null)throw new SQLException("Backup destination must have a parent directory");
        Files.createDirectories(parent);
        if(Files.exists(target))throw new SQLException("Backup already exists: "+target);
        try(PreparedStatement statement=conn.prepareStatement("VACUUM INTO ?")){
            statement.setString(1,target.toString());statement.execute();
        }
        if(!Files.isRegularFile(target)||Files.size(target)==0)throw new SQLException("SQLite did not create a complete backup at "+target);
    }
    synchronized int resetCount()throws SQLException {
        try(Statement statement=conn.createStatement();ResultSet rows=statement.executeQuery("SELECT COUNT(*) FROM resets")){
            return rows.next()?rows.getInt(1):0;
        }
    }
    synchronized int totalRecoveryCount()throws SQLException {
        try(Statement statement=conn.createStatement();ResultSet rows=statement.executeQuery("SELECT COUNT(*) FROM recovery WHERE claim_state=0")){
            return rows.next()?rows.getInt(1):0;
        }
    }
    @Override public void close()throws SQLException{conn.close();}
}
