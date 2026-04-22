package com.metaverse.coin.strategy.job.config;

import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;

/**
 * Local CLI for generating ENC(...) values without starting the Spring service.
 */
public final class SecretEncryptTool {

    private SecretEncryptTool() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        if (arguments.help) {
            printUsage();
            return;
        }

        String keyPath = firstNonBlank(arguments.keyPath, System.getenv("APP_RSA_PRIVATE_KEY_PATH"));
        if (keyPath == null) {
            throw new IllegalArgumentException("Missing private key path. Use --key or APP_RSA_PRIVATE_KEY_PATH.");
        }

        PrivateKey privateKey = SecretCrypto.loadPrivateKey(keyPath);
        if (arguments.decrypt) {
            String encrypted = arguments.readValue("ENC(...) value");
            System.out.println(SecretCrypto.decrypt(encrypted, privateKey));
            return;
        }

        String plaintext = arguments.readValue("secret value");
        System.out.println(SecretCrypto.encrypt(plaintext, SecretCrypto.derivePublicKey(privateKey)));
    }

    private static String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left;
        }
        if (right != null && !right.isBlank()) {
            return right;
        }
        return null;
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  SecretEncryptTool [--key <private-key.pem>] <plaintext>");
        System.out.println("  SecretEncryptTool [--key <private-key.pem>] --stdin");
        System.out.println("  SecretEncryptTool [--key <private-key.pem>] --decrypt <ENC(...)>");
        System.out.println();
        System.out.println("If --key is omitted, APP_RSA_PRIVATE_KEY_PATH is used.");
    }

    private static final class Arguments {
        private String keyPath;
        private String value;
        private boolean stdin;
        private boolean decrypt;
        private boolean help;

        static Arguments parse(String[] args) {
            Arguments arguments = new Arguments();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--key":
                        arguments.keyPath = requireNext(args, ++i, "--key");
                        break;
                    case "--stdin":
                        arguments.stdin = true;
                        break;
                    case "--decrypt":
                        arguments.decrypt = true;
                        if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                            arguments.value = args[++i];
                        }
                        break;
                    case "-h":
                    case "--help":
                        arguments.help = true;
                        break;
                    default:
                        if (arguments.value != null) {
                            throw new IllegalArgumentException("Only one secret value can be provided.");
                        }
                        arguments.value = arg;
                        break;
                }
            }
            return arguments;
        }

        String readValue(String prompt) throws Exception {
            if (stdin) {
                return new String(System.in.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
            }
            if (value != null) {
                return value;
            }
            Console console = System.console();
            if (console == null) {
                throw new IllegalArgumentException("Missing " + prompt + ". Provide an argument or use --stdin.");
            }
            char[] chars = console.readPassword("Enter %s: ", prompt);
            if (chars == null || chars.length == 0) {
                throw new IllegalArgumentException("Empty " + prompt + " is not allowed.");
            }
            return new String(chars);
        }

        private static String requireNext(String[] args, int index, String option) {
            if (index >= args.length || args[index].isBlank()) {
                throw new IllegalArgumentException(option + " requires a value.");
            }
            return args[index];
        }
    }
}
