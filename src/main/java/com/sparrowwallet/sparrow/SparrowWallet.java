package com.sparrowwallet.sparrow;

import com.beust.jcommander.JCommander;
import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Drongo;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.instance.InstanceException;
import com.sparrowwallet.sparrow.instance.InstanceList;
import com.sparrowwallet.sparrow.mweb.MwebServer;
import com.sparrowwallet.sparrow.terminal.SparrowTerminal;
import com.sun.javafx.application.PlatformImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.File;
import java.util.*;

public class SparrowWallet {
    public static final String APP_ID = "sparrow";
    public static final String APP_NAME = "Sparrow-LTC";
    public static final String APP_VERSION = "2.4.3";
    public static final String APP_VERSION_SUFFIX = "";
    public static final String APP_HOME_PROPERTY = "sparrow.home";
    public static final String NETWORK_ENV_PROPERTY = "SPARROW_NETWORK";
    public static final String COIN_ENV_PROPERTY = "SCRYPTO_COIN";
    public static final String COIN_SYSTEM_PROPERTY = "scrypto.coin";
    public static final String JPACKAGE_APP_PATH = "jpackage.app-path";

    private static Instance instance;

    public static void main(String[] argv) {
        if(System.getProperty(JPACKAGE_APP_PATH) != null) {
            String libDir = System.getProperty("java.home") + File.separator + "lib";
            System.setProperty("jna.boot.library.path", libDir);
            System.setProperty("jna.library.path", libDir);
            System.setProperty("jSerialComm.library.path", libDir);
            System.setProperty("org.usb4java.LibraryName", "usb4java");
            System.setProperty("java.library.path", libDir);
        }

        Args args = new Args();
        JCommander jCommander = JCommander.newBuilder().addObject(args).programName(APP_NAME.toLowerCase(Locale.ROOT)).acceptUnknownOptions(true).build();
        jCommander.parse(argv);
        if(args.help) {
            jCommander.usage();
            System.exit(0);
        }

        if(args.version) {
            System.out.println("Sparrow-LTC Wallet " + APP_VERSION);
            System.exit(0);
        }

        if(args.level != null) {
            Drongo.setRootLogLevel(args.level);
        }

        if(args.dir != null) {
            System.setProperty(APP_HOME_PROPERTY, args.dir);
            getLogger().info("Using configured Sparrow-LTC home folder of " + args.dir);
        }

        //Set the coin before any chain parameters, home folder or network are resolved. A packaged
        //flavor bakes -Dscrypto.coin=<name> into its JVM args; the arg and env are for development.
        if(args.coin != null) {
            Coin.set(args.coin);
        } else {
            String coinProperty = System.getProperty(COIN_SYSTEM_PROPERTY, System.getenv(COIN_ENV_PROPERTY));
            if(coinProperty != null) {
                try {
                    Coin.set(Coin.valueOf(coinProperty.toUpperCase(Locale.ROOT)));
                } catch(Exception e) {
                    getLogger().warn("Invalid " + COIN_SYSTEM_PROPERTY + "/" + COIN_ENV_PROPERTY + " value: " + coinProperty);
                }
            }
        }

        if(Coin.get() != Coin.LITECOIN) {
            getLogger().info("Using " + Coin.get() + " configuration");
        }

        if(args.network != null) {
            Network.set(args.network);
        } else {
            String envNetwork = System.getenv(NETWORK_ENV_PROPERTY);
            if(envNetwork != null) {
                try {
                    Network.set(Network.valueOf(envNetwork.toUpperCase(Locale.ROOT)));
                } catch(Exception e) {
                    getLogger().warn("Invalid " + NETWORK_ENV_PROPERTY + " property: " + envNetwork);
                }
            }
        }

        File testnetFlag = new File(Storage.getSparrowHome(), "network-" + Network.TESTNET.getName());
        if(testnetFlag.exists()) {
            Network.set(Network.TESTNET);
        }

        File testnet4Flag = new File(Storage.getSparrowHome(), "network-" + Network.TESTNET4.getName());
        if(testnet4Flag.exists()) {
            Network.set(Network.TESTNET4);
        }

        File signetFlag = new File(Storage.getSparrowHome(), "network-" + Network.SIGNET.getName());
        if(signetFlag.exists()) {
            Network.set(Network.SIGNET);
        }

        if(Network.get() != Network.MAINNET) {
            getLogger().info("Using " + Network.get() + " configuration");
        }

        List<String> fileUriArguments = jCommander.getUnknownOptions();

        try {
            instance = new Instance(fileUriArguments);
            instance.acquireLock(!fileUriArguments.isEmpty()); //If fileUriArguments is not empty, will exit app after sending fileUriArguments if lock cannot be acquired
        } catch(InstanceException e) {
            getLogger().error("Could not access application lock", e);
        }

        if(!fileUriArguments.isEmpty()) {
            AppServices.parseFileUriArguments(fileUriArguments);
        }

        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        if(args.terminal) {
            Interface.set(Interface.TERMINAL);
        }

        MwebServer.initialize();

        try {
            if(Interface.get() == Interface.TERMINAL) {
                PlatformImpl.setTaskbarApplication(false);
                Drongo.removeRootLogAppender("STDOUT");
                com.sun.javafx.application.LauncherImpl.launchApplication(SparrowTerminal.class, SparrowWalletPreloader.class, argv);
            } else {
                com.sun.javafx.application.LauncherImpl.launchApplication(SparrowDesktop.class, SparrowWalletPreloader.class, argv);
            }
        } catch(UnsupportedOperationException e) {
            Drongo.removeRootLogAppender("STDOUT");
            getLogger().error("Unable to launch application", e);
            System.out.println("No display detected. Use Sparrow-LTC Server on a headless (no display) system.");

            try {
                if(instance != null) {
                    instance.freeLock();
                }
            } catch(InstanceException instanceException) {
                getLogger().error("Unable to free instance lock", e);
            }
        }
    }

    public static Instance getSparrowInstance() {
        return instance;
    }

    private static Logger getLogger() {
        return LoggerFactory.getLogger(SparrowWallet.class);
    }

    public static class Instance extends InstanceList {
        private final List<String> fileUriArguments;

        public Instance(List<String> fileUriArguments) {
            super(SparrowWallet.APP_ID, true);
            this.fileUriArguments = fileUriArguments;
        }

        @Override
        protected void receiveMessageList(List<String> messageList) {
            if(messageList != null) {
                AppServices.parseFileUriArguments(messageList);
                AppServices.openFileUriArguments(null);
            }
        }

        @Override
        protected List<String> sendMessageList() {
            return fileUriArguments;
        }

        @Override
        protected void beforeExit() {
            getLogger().info("Opening files/URIs in already running instance, exiting...");
        }
    }
}
