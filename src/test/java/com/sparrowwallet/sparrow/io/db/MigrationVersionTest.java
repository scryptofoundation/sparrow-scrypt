package com.sparrowwallet.sparrow.io.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Arrays;

public class MigrationVersionTest {
    //V10 to V12 are taken by feature/coin-axis (Coin, LitVm, PendingMint), so upstream's silent payments migration is V13
    private static final File MIGRATIONS = new File("src/main/resources/" + DbPersistence.MIGRATION_RESOURCES_DIR);

    @TempDir
    File tempDir;

    private Flyway flyway(File db, String target) {
        var configuration = Flyway.configure().dataSource(url(db), "", "").locations("filesystem:" + MIGRATIONS.getAbsolutePath()).failOnMissingLocations(true);
        if(target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static String url(File db) {
        return "jdbc:h2:" + db.getPath() + ";DATABASE_TO_UPPER=false";
    }

    @Test
    public void silentPaymentsMigrationIsV13() {
        Assertions.assertTrue(new File(MIGRATIONS, "V13__SilentPayments.sql").exists());
        String[] reserved = MIGRATIONS.list((dir, name) -> name.matches("V1[0-2]__.*"));
        Assertions.assertEquals(0, reserved.length, "V10 to V12 belong to feature/coin-axis: " + Arrays.toString(reserved));
    }

    @Test
    public void freshDatabaseMigratesToV13() {
        File db = new File(tempDir, "fresh");
        flyway(db, null).migrate();
        Assertions.assertEquals("13", flyway(db, null).info().current().getVersion().getVersion());
    }

    @Test
    public void ltcWalletAtV9MigratesToV13() throws Exception {
        File db = new File(tempDir, "ltc");
        flyway(db, "9").migrate();
        Assertions.assertEquals("9", flyway(db, null).info().current().getVersion().getVersion());

        flyway(db, null).migrate();
        Assertions.assertEquals("13", flyway(db, null).info().current().getVersion().getVersion());
        try(Connection connection = DriverManager.getConnection(url(db), "", "");
            ResultSet columns = connection.getMetaData().getColumns(null, null, "keystore", "silentPaymentScanAddress")) {
            Assertions.assertTrue(columns.next());
        }
    }
}
