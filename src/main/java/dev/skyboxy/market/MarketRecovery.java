package dev.skyboxy.market;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.*;

/** Snapshots recoverable inventory before any world reset; resets are resumable. */
final class MarketRecovery implements Listener {
    private final SkyBoxyMarket p;
    private final NamespacedKey receiptKey;
    private final NamespacedKey receiptAmountKey;
    private final Set<String> busy=new HashSet<>();
    private final Set<Long> pendingReceipts=new HashSet<>();
    private final Map<UUID,ClaimAllJob> claimJobs=new HashMap<>();
    MarketRecovery(SkyBoxyMarket p){this.p=p;receiptKey=new NamespacedKey(p,"recovery_receipt");receiptAmountKey=new NamespacedKey(p,"recovery_receipt_amount");}
    void reset(Booth b,String actor){
        if(b.owner==null||busy.contains(b.id))return;
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Reset must execute on primary thread");
        try{
            p.db.beginReset(b);
            p.db.audit(actor,"reset-start",b.id+" lease="+b.leaseId);
            process(b,p.db.resets().stream().filter(r->r.booth().equals(b.id)).findFirst().orElseThrow());
        }catch(Exception ex){p.getLogger().severe("RESET HALTED at "+b.id+" (preserved for retry): "+ex);}
    }
    void resumeResets(){
        try{for(MarketDatabase.Reset r:p.db.resets()){
            Booth b=p.byId(r.booth());
            if(b==null){p.getLogger().severe("Orphan reset record "+r.booth()+" needs admin intervention");continue;}
            b.resetting=true;
            try{process(b,r);}
            catch(Exception ex){p.getLogger().severe("Could not resume reset for "+b.id+" (journal preserved for retry): "+ex);}
        }}catch(Exception ex){p.getLogger().severe("Failed to recover interrupted resets: "+ex);}
    }
    private void process(Booth b,MarketDatabase.Reset r) throws Exception {
        if(!busy.add(b.id))return;
        boolean restoreScheduled=false;
        try{
            World w=Bukkit.getWorld(b.world);if(w==null)throw new IllegalStateException("World not loaded: "+b.world);
            StyleTemplates.Template t=p.templates.get(b.style);
            if(t==null)throw new IllegalStateException("Missing registered style schematic: "+b.style);
            loadBoothChunks(b,w);
            int restoreCursor=r.restoreCursor();
            if("SNAPSHOTTING".equals(r.phase())){
                snapshot(b,r.owner(),r.lease(),w);
                p.db.resetPhase(b,"RESTORING");
                restoreCursor=0;
            }else if(!"RESTORING".equals(r.phase())){
                throw new IllegalStateException("Unknown reset phase "+r.phase()+" for booth "+b.id);
            }
            // Items have been committed before world destruction. Shop provider cleanup must succeed first.
            if(p.quickShop!=null)p.quickShop.removeShops(b);
            removeBoothEntities(b,w);
            startRestore(b,w,t,r,restoreCursor);
            restoreScheduled=true;
        }finally{if(!restoreScheduled)busy.remove(b.id);}
    }
    private void startRestore(Booth b,World world,StyleTemplates.Template template,MarketDatabase.Reset reset,int cursor){
        final org.bukkit.scheduler.BukkitTask[] task=new org.bukkit.scheduler.BukkitTask[1];
        final int[] progress={cursor};
        task[0]=Bukkit.getScheduler().runTaskTimer(p,()->{
            try{
                loadBoothChunks(b,world);
                int budget=Math.max(1,Math.min(4096,p.getConfig().getInt("market.reset-blocks-per-tick",256)));
                int next=template.restoreBatch(b,progress[0],budget);
                // Flush edited world chunks before advancing SQLite. Otherwise a hard process
                // stop could persist the cursor but lose recent chunk writes, skipping blocks on resume.
                world.save(true);
                // If the cursor commit fails after the world flush, the last batch is replayed safely.
                p.db.resetCursor(b,next);
                progress[0]=next;
                if(progress[0]>=template.cells.size()){
                    task[0].cancel();
                    MarketStyle style=p.style(b);
                    if(style!=null)p.db.cooldown(reset.owner(),b.style,System.currentTimeMillis()+style.cooldownHours*3_600_000L);
                    p.db.finishReset(b);
                    p.holograms.refresh(b);
                    p.db.audit("SYSTEM","reset-finished",b.id+" lease="+reset.lease());
                    busy.remove(b.id);
                    p.getLogger().info("Reset completed for booth "+b.id+" in resumable batches.");
                }
            }catch(Exception ex){
                task[0].cancel();busy.remove(b.id);
                p.getLogger().severe("RESET HALTED at "+b.id+" (journal preserved for retry): "+ex);
            }
        },1L,1L);
    }
    private void loadBoothChunks(Booth b,World w){
        // A persisted reset may point at chunks outside the server's startup radius.
        // Load them before snapshot/restore instead of abandoning the journal until a
        // player happens to visit the booth.
        for(int x=b.x>>4;x<=(b.x+b.width-1)>>4;x++)for(int z=b.z>>4;z<=(b.z+b.length-1)>>4;z++){
            if(!w.isChunkLoaded(x,z))w.getChunkAt(x,z);
            if(!w.isChunkLoaded(x,z))throw new IllegalStateException("Could not load booth chunk "+x+","+z);
        }
    }
    private void snapshot(Booth b,UUID owner,String lease,World w)throws Exception {
        // Each location+slot is stable across restart, preventing duplicate capture when retrying.
        int batchSize=Math.max(1,Math.min(2048,p.getConfig().getInt("market.reset-batch-size",256)));
        List<MarketDatabase.RecoveryItem> batch=new ArrayList<>(batchSize);
        Set<String> capturedContainers=new HashSet<>();
        for(MarketDatabase.Placed placed:p.db.placed(b)){
            ItemStack stack=placed.item();
            if(stack==null&&placed.type().isItem())stack=new ItemStack(placed.type(),1);
            if(stack!=null&&!stack.getType().isAir()){
                stack=stack.clone();stack.setAmount(1);
                Block placedBlock=w.getBlockAt(placed.x(),placed.y(),placed.z());
                if(placedBlock.getType()==placed.type())stack=capturePlacedBlockState(stack,placedBlock);
                queueRecovery(batch,batchSize,owner,b,lease,"block:"+placed.x()+":"+placed.y()+":"+placed.z(),0,stack);
            }
        }
        for(int x=b.x;x<b.x+b.width;x++)for(int y=b.y;y<b.y+b.height;y++)for(int z=b.z;z<b.z+b.length;z++){
            Block block=w.getBlockAt(x,y,z);
            BlockState state=block.getState();
            if(state instanceof Container cont){
                Inventory inv;
                Block source=block;
                if(state instanceof Chest chest){
                    inv=chest.getInventory();
                    if(inv.getHolder() instanceof DoubleChest doubleChest){
                        if(!(doubleChest.getLeftSide() instanceof Chest left)
                                ||!(doubleChest.getRightSide() instanceof Chest right))
                            throw new IllegalStateException("Cannot identify both block halves of a double chest at "+block.getLocation());
                        Block leftBlock=left.getBlock(),rightBlock=right.getBlock();
                        if(!b.contains(leftBlock.getLocation())||!b.contains(rightBlock.getLocation()))
                            throw new IllegalStateException("Double chest crosses booth boundary at "+leftBlock.getLocation()+" / "+rightBlock.getLocation());
                        // The Bukkit double-chest inventory is the same 54-slot view from both halves.
                        // Pick a coordinate-stable key and deduplicate the shared view from either half.
                        source=compareBlocks(leftBlock,rightBlock)<=0?leftBlock:rightBlock;
                    }
                }else inv=cont.getSnapshotInventory();
                String sourceKey="container:"+source.getX()+":"+source.getY()+":"+source.getZ();
                if(!capturedContainers.add(sourceKey))continue;
                ItemStack[] contents=inv.getContents();
                for(int i=0;i<contents.length;i++)queueRecovery(batch,batchSize,owner,b,lease,sourceKey,i,contents[i]);
            }
        }
        for(Entity e:boothEntities(b,w)){
            if(!b.contains(e.getLocation())||p.holograms.isMarketEntity(e))continue;
            String key="entity:"+e.getUniqueId();
            if(e instanceof Item drop)queueRecovery(batch,batchSize,owner,b,lease,key,0,drop.getItemStack());
            else if(e instanceof ItemFrame frame)queueRecovery(batch,batchSize,owner,b,lease,key,0,frame.getItem());
            else if(e instanceof ArmorStand stand){
                var eq=stand.getEquipment();
                if(eq!=null){int i=0;for(ItemStack stack:new ItemStack[]{eq.getHelmet(),eq.getChestplate(),eq.getLeggings(),eq.getBoots(),eq.getItemInMainHand(),eq.getItemInOffHand()})queueRecovery(batch,batchSize,owner,b,lease,key,i++,stack);}
            }
        }
        p.db.recoveryBatch(owner,b,lease,batch);
    }
    private ItemStack capturePlacedBlockState(ItemStack item,Block block){
        ItemMeta meta=item.getItemMeta();
        if(!(meta instanceof BlockStateMeta blockMeta))return item;
        try{
            BlockState current=block.getState();
            blockMeta.setBlockState(current);
            if(current instanceof Container && blockMeta.getBlockState() instanceof Container itemContainer){
                // The live inventory is journaled below as slot entries. Keep the returned
                // container shell empty so shulker-style block item metadata cannot duplicate it.
                itemContainer.getSnapshotInventory().clear();
                blockMeta.setBlockState((BlockState)itemContainer);
            }
            item.setItemMeta(meta);
        }catch(RuntimeException ex){
            p.getLogger().warning("Could not capture block-state metadata at "+block.getLocation()+"; preserving the original placed item: "+ex.getMessage());
        }
        return item;
    }
    private static int compareBlocks(Block first,Block second){
        int x=Integer.compare(first.getX(),second.getX());if(x!=0)return x;
        int y=Integer.compare(first.getY(),second.getY());if(y!=0)return y;
        return Integer.compare(first.getZ(),second.getZ());
    }
    private void queueRecovery(List<MarketDatabase.RecoveryItem> batch,int batchSize,UUID owner,Booth booth,String lease,
                               String source,int slot,ItemStack item)throws Exception {
        if(item==null||item.getType().isAir()||item.getAmount()<=0)return;
        batch.add(new MarketDatabase.RecoveryItem(source,slot,item.clone()));
        if(batch.size()>=batchSize){p.db.recoveryBatch(owner,booth,lease,batch);batch.clear();}
    }
    private void removeBoothEntities(Booth b,World w){
        for(Entity e:boothEntities(b,w)){
            if(!b.contains(e.getLocation())||p.holograms.isMarketEntity(e)||e instanceof Player)continue;
            if(e instanceof Item||e instanceof ItemFrame||e instanceof ArmorStand||e instanceof Painting||e instanceof Interaction)e.remove();
        }
    }
    private Collection<Entity> boothEntities(Booth b,World w){
        Location center=new Location(w,b.x+b.width/2.0,b.y+b.height/2.0,b.z+b.length/2.0);
        return w.getNearbyEntities(center,b.width/2.0+0.5,b.height/2.0+0.5,b.length/2.0+0.5);
    }
    boolean isClaimingAll(UUID owner){return claimJobs.containsKey(owner);}
    boolean claimAll(Player player,Runnable finished){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Recovery delivery must execute on the primary thread");
        if(!player.isOnline())return false;
        if(isClaimingAll(player.getUniqueId())){p.tell(player,"&eRecovery is already in progress.");return false;}
        try{
            long upperBound=p.db.recoveryUpperBound(player.getUniqueId());
            if(upperBound==0){p.tell(player,"&eYour recovery storage is empty.");finished.run();return false;}
            ClaimAllJob job=new ClaimAllJob(player,upperBound,finished);
            claimJobs.put(player.getUniqueId(),job);
            try{job.runTaskTimer(p,1L,1L);}catch(RuntimeException ex){claimJobs.remove(player.getUniqueId(),job);throw ex;}
            p.tell(player,"&aRecovering items that fit from all pages...");return true;
        }catch(Exception ex){p.getLogger().warning("Could not start all-storage recovery: "+ex);p.tell(player,"&cRecovery could not start; your items remain in storage.");return false;}
    }
    void cancelClaims(){
        for(ClaimAllJob job:new ArrayList<>(claimJobs.values()))job.cancel();
        claimJobs.clear();
    }
    private final class ClaimAllJob extends BukkitRunnable {
        private final Player player;
        private final long upperBound;
        private final Runnable finished;
        private final Deque<Long> keys=new ArrayDeque<>();
        private long cursor;
        ClaimAllJob(Player player,long upperBound,Runnable finished){this.player=player;this.upperBound=upperBound;this.finished=finished;}
        @Override public void run(){
            if(!player.isOnline()){finish(null);return;}
            if(!player.hasPermission("skyboxymarket.use")&&!player.hasPermission("skyboxymarket.admin")){finish("&eRecovery stopped because your market permission changed.");return;}
            try{
                // Bound both ledger reads and inventory saves. A fixed upper bound excludes
                // new resets and split remainders, so this pass cannot grow while running.
                for(int checked=0;checked<16;checked++){
                    if(keys.isEmpty())keys.addAll(p.db.recoveryKeysAfter(player.getUniqueId(),cursor,upperBound,16));
                    if(keys.isEmpty()){finish("&aRecovery finished. Items that did not fit remain in storage.");return;}
                    long id=keys.removeFirst();cursor=id;
                    ClaimResult result=deliver(player,id,false);
                    if(result==ClaimResult.DELIVERED)return;
                    if(result==ClaimResult.FAILED){finish("&eRecovery stopped after a delivery problem. Remaining items stay in storage; contact staff.");return;}
                }
            }catch(Exception ex){p.getLogger().severe("All-storage recovery failed: "+ex);finish("&eRecovery stopped. Remaining items stay in storage; contact staff.");}
        }
        private void finish(String message){
            if(!claimJobs.remove(player.getUniqueId(),this))return;
            cancel();
            if(player.isOnline()){
                if(message!=null)p.tell(player,message);
                try{finished.run();}catch(RuntimeException ex){p.getLogger().warning("Recovery completion menu could not refresh: "+ex);}
            }
        }
    }
    private enum ClaimResult { DELIVERED, NO_SPACE, UNAVAILABLE, FAILED }
    boolean claim(Player player,long id,ItemStack stack){
        if(isClaimingAll(player.getUniqueId())){p.tell(player,"&eRecovery is already in progress.");return false;}
        return deliver(player,id,true)==ClaimResult.DELIVERED;
    }
    private ClaimResult deliver(Player player,long id,boolean feedback){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Recovery delivery must execute on the primary thread");
        if(!player.isOnline())return ClaimResult.UNAVAILABLE;
        boolean claimStarted=false, receiptSaved=false, delivered=false;
        ItemStack[] before=Arrays.stream(player.getInventory().getStorageContents())
                .map(item->item==null?null:item.clone()).toArray(ItemStack[]::new);
        try{
            // The displayed item may be stale. Use the exact owner-scoped ledger payload
            // for both delivery and the compare-and-set claim transition.
            byte[] payload=p.db.recoveryPayload(id,player.getUniqueId());
            if(payload==null){if(feedback)p.tell(player,"&cThis item is already claimed or being delivered.");return ClaimResult.UNAVAILABLE;}
            ItemStack stack=ItemStack.deserializeBytes(payload);
            int limit=Math.min(stack.getMaxStackSize(),player.getInventory().getMaxStackSize());
            int amount=Math.min(stack.getAmount(),availableCapacity(before,stack,limit));
            if(amount<=0){if(feedback)p.tell(player,"&cInventory full, item remains in recovery.");return ClaimResult.NO_SPACE;}
            ItemStack portion=stack.clone();portion.setAmount(amount);
            ItemStack remainder=null;
            if(amount<stack.getAmount()){remainder=stack.clone();remainder.setAmount(stack.getAmount()-amount);}
            ItemStack[] staged=stageDelivery(before,portion,id,limit);
            if(!p.db.beginClaim(id,player.getUniqueId(),payload,portion.serializeAsBytes(),remainder==null?null:remainder.serializeAsBytes())){
                if(feedback)p.tell(player,"&cThis item is already claimed or being delivered.");return ClaimResult.UNAVAILABLE;
            }
            claimStarted=true;
            pendingReceipts.add(id);
            player.getInventory().setStorageContents(staged);
            // Persist the receipt with the player's data before completing the ledger row.
            // A crash before this save leaves the row in-flight and can be safely reopened;
            // a crash after it leaves a durable token that the join handler can reconcile.
            player.saveData();
            receiptSaved=true;
            if(!p.db.completeClaim(id,player.getUniqueId())&&!p.db.claimCompleted(id,player.getUniqueId()))
                throw new IllegalStateException("Recovery receipt was saved but its ledger row could not be finalized");
            delivered=true;
            pendingReceipts.remove(id);
            p.db.audit(player.getUniqueId().toString(),"recover-delivery-start","entry="+id+" amount="+amount);
            removeReceipt(player,id);
            player.saveData();
            if(feedback&&remainder!=null)p.tell(player,"&aRecovered "+amount+" item(s); "+remainder.getAmount()+" remain in recovery storage.");
            return ClaimResult.DELIVERED;
        }catch(Exception ex){
            if(claimStarted&&!receiptSaved&&!delivered){
                // If inventory delivery failed before its durable save, restore and persist the
                // original inventory before making the recovery row available again.
                try{
                    player.getInventory().setStorageContents(before);
                    player.saveData();
                    p.db.releaseClaim(id,player.getUniqueId());
                    pendingReceipts.remove(id);
                }catch(Exception rollback){ex.addSuppressed(rollback);}
            }
            p.getLogger().severe("Recovery claim failed: "+ex);
            if(receiptSaved&&!delivered)p.tell(player,"&eThe saved recovery item is locked until its ledger entry is finalized. Keep it in your inventory and relog if this persists.");
            else if(delivered)p.tell(player,"&eItem delivered; receipt cleanup will finish when you reconnect.");
            return delivered?ClaimResult.DELIVERED:ClaimResult.FAILED;
        }
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void joined(PlayerJoinEvent event){
        Player player=event.getPlayer();
        try{
            Map<Long,ItemStack> receipts=new HashMap<>();
            for(ItemStack item:player.getInventory().getContents()){
                Long id=receiptId(item);if(id!=null)receipts.put(id,item);
            }
            // Keep saved tokens locked even if SQLite fails during reconciliation.
            pendingReceipts.addAll(receipts.keySet());
            for(long id:p.db.inFlightClaims(player.getUniqueId())){
                boolean present=receipts.containsKey(id);
                if(p.db.reconcileClaim(id,player.getUniqueId(),present)){
                    pendingReceipts.remove(id);
                    if(present){removeReceipt(player,id);p.db.audit("SYSTEM","recover-claim","entry="+id+" owner="+player.getUniqueId());}
                    else p.db.audit("SYSTEM","recover-delivery-reopened","entry="+id+" owner="+player.getUniqueId());
                }
            }
            for(Map.Entry<Long,ItemStack> receipt:receipts.entrySet()){
                if(p.db.claimCompleted(receipt.getKey(),player.getUniqueId())){
                    pendingReceipts.remove(receipt.getKey());removeReceipt(player,receipt.getKey());
                }
            }
        }catch(Exception ex){p.getLogger().severe("Could not reconcile recovery receipts for "+player.getUniqueId()+": "+ex);}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void dropReceipt(PlayerDropItemEvent event){
        if(isPendingReceipt(event.getItemDrop().getItemStack()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void usePendingReceipt(PlayerInteractEvent event){
        if(isPendingReceipt(event.getItem()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void consumePendingReceipt(PlayerItemConsumeEvent event){
        if(isPendingReceipt(event.getItem()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void placePendingReceipt(PlayerInteractEntityEvent event){
        if(isPendingReceipt(event.getPlayer().getInventory().getItemInMainHand())
                ||isPendingReceipt(event.getPlayer().getInventory().getItemInOffHand()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void swapPendingReceipt(PlayerSwapHandItemsEvent event){
        if(isPendingReceipt(event.getMainHandItem())||isPendingReceipt(event.getOffHandItem()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void movePendingReceipt(InventoryClickEvent event){
        if(!(event.getWhoClicked() instanceof Player))return;
        if(isPendingReceipt(event.getCurrentItem())||isPendingReceipt(event.getCursor())){event.setCancelled(true);return;}
        int hotbar=event.getHotbarButton();
        if(hotbar>=0&&hotbar<9&&isPendingReceipt(event.getWhoClicked().getInventory().getItem(hotbar)))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void dragPendingReceipt(InventoryDragEvent event){
        if(isPendingReceipt(event.getOldCursor())||event.getNewItems().values().stream().anyMatch(this::isPendingReceipt))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void deathReceipt(PlayerDeathEvent event){
        if(event.isCancelled())return;
        Player player=event.getEntity();UUID owner=player.getUniqueId();
        try{
            Set<Long> pending=new HashSet<>(p.db.inFlightClaims(owner)),receipts=new HashSet<>();
            for(ItemStack item:event.getDrops()){Long id=receiptId(item);if(id!=null)receipts.add(id);}
            for(ItemStack item:event.getItemsToKeep()){Long id=receiptId(item);if(id!=null)receipts.add(id);}
            for(ItemStack item:player.getInventory().getContents()){Long id=receiptId(item);if(id!=null)receipts.add(id);}
            for(long id:receipts){
                boolean uncommitted=pending.contains(id);
                if(!uncommitted&&!p.db.claimCompleted(id,owner))continue;
                cleanDeathItems(event.getDrops(),id,uncommitted);
                cleanDeathItems(event.getItemsToKeep(),id,uncommitted);
                for(int slot=0;slot<player.getInventory().getSize();slot++){
                    ItemStack item=player.getInventory().getItem(slot);
                    if(!Objects.equals(receiptId(item),id))continue;
                    if(uncommitted&&subtractReceipt(item))player.getInventory().setItem(slot,null);
                    else {removeReceipt(item);player.getInventory().setItem(slot,item);}
                }
                if(uncommitted){
                    // Save the removal before reopening the ledger. A stop between these
                    // operations leaves a pending row with no token, which join safely reopens.
                    player.saveData();
                    if(!p.db.reconcileClaim(id,owner,false))throw new IllegalStateException("Pending death claim changed before reopen: "+id);
                    pendingReceipts.remove(id);
                    p.db.audit("SYSTEM","recover-death-reopened","entry="+id+" owner="+owner);
                }else pendingReceipts.remove(id);
            }
        }catch(Exception ex){p.getLogger().severe("Could not return a recovery delivery after death: "+ex);}
    }
    private void cleanDeathItems(List<ItemStack> items,long id,boolean uncommitted){
        for(Iterator<ItemStack> iterator=items.iterator();iterator.hasNext();){
            ItemStack item=iterator.next();if(!Objects.equals(receiptId(item),id))continue;
            if(uncommitted&&subtractReceipt(item))iterator.remove();else removeReceipt(item);
        }
    }
    private boolean subtractReceipt(ItemStack item){
        Integer amount=item.getItemMeta().getPersistentDataContainer().get(receiptAmountKey,PersistentDataType.INTEGER);
        int delivered=amount==null?item.getAmount():Math.max(0,Math.min(item.getAmount(),amount));
        item.setAmount(item.getAmount()-delivered);return item.getAmount()<=0;
    }
    private Long receiptId(ItemStack item){
        if(item==null||!item.hasItemMeta())return null;
        return item.getItemMeta().getPersistentDataContainer().get(receiptKey,PersistentDataType.LONG);
    }
    private boolean isPendingReceipt(ItemStack item){Long id=receiptId(item);return id!=null&&pendingReceipts.contains(id);}
    private void removeReceipt(Player player,long id){
        for(int slot=0;slot<player.getInventory().getSize();slot++){
            ItemStack item=player.getInventory().getItem(slot);
            if(Objects.equals(receiptId(item),id)){removeReceipt(item);player.getInventory().setItem(slot,item);}
        }
    }
    private void removeReceipt(ItemStack item){
        if(item==null||!item.hasItemMeta())return;
        ItemMeta meta=item.getItemMeta();meta.getPersistentDataContainer().remove(receiptKey);meta.getPersistentDataContainer().remove(receiptAmountKey);item.setItemMeta(meta);
    }
    private int availableCapacity(ItemStack[] contents,ItemStack stack,int limit){
        int space=0;
        for(ItemStack slot:contents){
            if(slot==null||slot.getType().isAir())space+=limit;
            else if(receiptId(slot)==null&&slot.isSimilar(stack))space+=Math.max(0,limit-slot.getAmount());
            if(space>=stack.getAmount())return stack.getAmount();
        }
        return space;
    }
    private ItemStack[] stageDelivery(ItemStack[] before,ItemStack portion,long id,int limit){
        ItemStack[] staged=Arrays.stream(before).map(item->item==null?null:item.clone()).toArray(ItemStack[]::new);
        int remaining=portion.getAmount();
        // Fill matching stacks first, then empty slots. Tag only the new quantity in
        // each stack so a pending death can retain all pre-existing player property.
        for(int pass=0;pass<2&&remaining>0;pass++)for(int slot=0;slot<staged.length&&remaining>0;slot++){
            ItemStack existing=staged[slot];boolean empty=existing==null||existing.getType().isAir();
            if(pass==0?(empty||receiptId(existing)!=null||!existing.isSimilar(portion)):!empty)continue;
            int baseline=empty?0:existing.getAmount(),amount=Math.min(remaining,Math.max(0,limit-baseline));
            if(amount==0)continue;
            ItemStack item=empty?portion.clone():existing;item.setAmount(baseline+amount);ItemMeta meta=item.getItemMeta();
            meta.getPersistentDataContainer().set(receiptKey,PersistentDataType.LONG,id);
            meta.getPersistentDataContainer().set(receiptAmountKey,PersistentDataType.INTEGER,amount);item.setItemMeta(meta);
            staged[slot]=item;remaining-=amount;
        }
        if(remaining!=0)throw new IllegalStateException("Recovery capacity changed before delivery");
        return staged;
    }
}
