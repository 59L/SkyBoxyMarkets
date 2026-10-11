package dev.skyboxy.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class MarketDatabasePaymentTest {
    @TempDir Path directory;

    @Test
    void interruptedProviderAndRefundBoundariesBecomeReviewableAfterRestart() throws Exception {
        Path file=directory.resolve("payments.db");
        UUID player=UUID.randomUUID();String prepared,charged,refund;
        try(MarketDatabase database=new MarketDatabase(file.toFile())){
            prepared=database.createPaymentIntent(player,"booth-a","RENT",25.0,"EssentialsX Economy");
            charged=database.createPaymentIntent(player,"booth-b","RENEW",5.0,"EssentialsX Economy");
            refund=database.createPaymentIntent(player,"booth-c","WALLS",2.0,"EssentialsX Economy");
            database.transitionPaymentIntent(charged,"PREPARED","CHARGED","Provider accepted withdrawal");
            database.transitionPaymentIntent(refund,"PREPARED","CHARGED","Provider accepted withdrawal");
            database.transitionPaymentIntent(refund,"CHARGED","REFUND_PENDING","Refund started");
        }

        try(MarketDatabase restarted=new MarketDatabase(file.toFile())){
            assertEquals(3,restarted.reviewInterruptedPaymentIntents());
            assertEquals("REVIEW_REQUIRED",restarted.paymentIntent(prepared).status());
            assertEquals("REVIEW_REQUIRED",restarted.paymentIntent(charged).status());
            assertEquals("Interrupted while state was REFUND_PENDING",restarted.paymentIntent(refund).detail());
            assertEquals(3,restarted.unresolvedPaymentIntentCount());
            assertEquals(3,restarted.paymentIntents(0,10).size());

            restarted.resolvePaymentIntent(prepared,MarketDatabase.PaymentResolution.NO_CHARGE_VERIFIED,UUID.randomUUID());
            assertEquals("NO_CHARGE_VERIFIED",restarted.paymentIntent(prepared).status());
            assertEquals(2,restarted.unresolvedPaymentIntentCount());
            assertThrows(SQLException.class,()->restarted.resolvePaymentIntent(prepared,MarketDatabase.PaymentResolution.REFUND_VERIFIED,UUID.randomUUID()));
        }
    }

    @Test
    void rejectsInvalidPaymentAmountsBeforeCreatingAnIntent() throws Exception {
        try(MarketDatabase database=new MarketDatabase(directory.resolve("invalid-payment.db").toFile())){
            assertThrows(SQLException.class,()->database.createPaymentIntent(UUID.randomUUID(),"booth","RENT",Double.NaN,"test"));
            assertThrows(SQLException.class,()->database.createPaymentIntent(UUID.randomUUID(),"booth","RENT",-1,"test"));
            assertEquals(0,database.unresolvedPaymentIntentCount());
        }
    }

    @Test
    void marketplaceCommitFailureKeepsChargedIntentForRepair() throws Exception {
        try(MarketDatabase database=new MarketDatabase(directory.resolve("failed-commit.db").toFile())){
            Booth booth=new Booth("booth","world","normal",0,64,0,11,11,11,0);database.save(booth);
            String intent=database.createPaymentIntent(UUID.randomUUID(),booth.id,"RENT",10,"test provider");
            database.transitionPaymentIntent(intent,"PREPARED","CHARGED","Withdrawal accepted");
            Connection connection=connection(database);
            try(Statement statement=connection.createStatement()){
                statement.execute("CREATE TRIGGER reject_marketplace_rent BEFORE UPDATE ON booths BEGIN SELECT RAISE(ABORT,'injected rental database failure'); END");
            }

            assertThrows(SQLException.class,()->database.commitRental(booth,UUID.randomUUID(),"lease",100,200,intent));
            assertNull(booth.owner);
            assertEquals("CHARGED",database.paymentIntent(intent).status());
            assertEquals(1,database.reviewInterruptedPaymentIntents());
            assertEquals("REVIEW_REQUIRED",database.paymentIntent(intent).status());
        }
    }

    @Test
    void rentalRenewalAndWallCommitMarkTheirIntentsAppliedAtomically() throws Exception {
        try(MarketDatabase database=new MarketDatabase(directory.resolve("applied-payment.db").toFile())){
            Booth booth=new Booth("booth","world","normal",0,64,0,11,11,11,0);database.save(booth);
            UUID owner=UUID.randomUUID();
            String rent=chargedIntent(database,owner,booth,"RENT");
            database.commitRental(booth,owner,"lease",1000,2000,rent);
            assertEquals("APPLIED",database.paymentIntent(rent).status());

            String renewal=chargedIntent(database,owner,booth,"RENEW");
            database.commitRenewal(booth,1500,owner,renewal);
            assertEquals("APPLIED",database.paymentIntent(renewal).status());

            String walls=chargedIntent(database,owner,booth,"WALLS");
            database.commitWallChange(booth,"BLUE",500,owner,walls);
            assertEquals("APPLIED",database.paymentIntent(walls).status());
            Booth persisted=database.loadBooths().getFirst();
            assertEquals(1500,persisted.expiry);
            assertEquals("BLUE",persisted.wallColor);
        }
    }

    private static String chargedIntent(MarketDatabase database,UUID owner,Booth booth,String action) throws Exception {
        String intent=database.createPaymentIntent(owner,booth.id,action,4,"test provider");
        database.transitionPaymentIntent(intent,"PREPARED","CHARGED","Withdrawal accepted");
        return intent;
    }

    private static Connection connection(MarketDatabase database) throws Exception {
        Field field=MarketDatabase.class.getDeclaredField("conn");field.setAccessible(true);return (Connection)field.get(database);
    }
}
