package com.loyalty;

import com.loyalty.db.Database;
import com.loyalty.model.Reward;
import com.loyalty.model.Tier;
import com.loyalty.service.BalanceResponse;
import com.loyalty.service.EarnResult;
import com.loyalty.service.LoyaltyService;
import com.loyalty.service.RedeemResult;
import com.loyalty.service.RefundResult;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Command-line interface over the same {@link LoyaltyService} the REST API uses — no server
 * required. Because all the business logic lives in the service layer, the CLI and the web layer
 * share it with no duplication.
 *
 * <p>Usage: {@code points <command> --flag=value ...}, e.g. {@code points balance --user=alice}.
 * Talks directly to the database (default {@code loyalty.db}, overridable via {@code LOYALTY_DB_URL}).
 */
public class Cli {

    private final LoyaltyService service;
    private final PrintStream out;
    private final PrintStream err;

    public Cli(LoyaltyService service, PrintStream out, PrintStream err) {
        this.service = service;
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        String url = System.getenv("LOYALTY_DB_URL");
        try (Database db = new Database(url != null ? url : Database.DEFAULT_URL)) {
            System.exit(new Cli(new LoyaltyService(db), System.out, System.err).run(args));
        }
    }

    /** Runs one command. Returns a process exit code: 0 ok, 1 operation error, 2 usage error. */
    public int run(String[] args) {
        if (args.length == 0) {
            printUsage(err);
            return 2;
        }
        String command = args[0];
        try {
            Map<String, String> flags = parseFlags(args);
            switch (command) {
                case "earn" -> earn(flags);
                case "balance" -> balance(flags);
                case "redeem" -> redeem(flags);
                case "refund" -> refund(flags);
                case "rewards" -> rewards();
                case "tiers" -> tiers();
                case "help", "-h", "--help" -> printUsage(out);
                default -> {
                    err.println("Unknown command: " + command);
                    printUsage(err);
                    return 2;
                }
            }
            return 0;
        } catch (UsageException e) {
            err.println(e.getMessage());
            printUsage(err);
            return 2;
        } catch (RuntimeException e) {
            err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private void earn(Map<String, String> flags) {
        EarnResult r = service.earn(
                require(flags, "user"),
                require(flags, "purchase-id"),
                new BigDecimal(require(flags, "amount")),
                optionalDate(flags, "date"));
        out.printf("Earned %d points for %s (purchase %s); expires %s%n",
                r.pointsEarned(), r.customerId(), r.purchaseId(), r.expiresAt());
    }

    private void balance(Map<String, String> flags) {
        BalanceResponse r = service.balance(require(flags, "user"), optionalDate(flags, "as-of"));
        out.printf("%s: %d points, tier %s (12-month spend %d) as of %s%n",
                r.customerId(), r.balance(), r.tier(), r.spendLast12Months(), r.asOf());
    }

    private void redeem(Map<String, String> flags) {
        RedeemResult r = service.redeem(
                require(flags, "user"), require(flags, "reward"), optionalDate(flags, "date"));
        out.printf("%s redeemed %s for %d points; %d remaining%n",
                r.customerId(), r.rewardId(), r.pointsSpent(), r.balanceRemaining());
    }

    private void refund(Map<String, String> flags) {
        RefundResult r = service.refund(
                require(flags, "user"), require(flags, "purchase-id"), optionalDate(flags, "date"));
        out.printf("Refunded %s for %s: reversed %d points, debt +%d; balance now %d%n",
                r.purchaseId(), r.customerId(), r.pointsReversed(), r.debtIncurred(), r.balanceRemaining());
    }

    private void rewards() {
        out.println("Rewards:");
        for (Reward rw : service.catalog()) {
            out.printf("  %-14s %5d pts  %s%n", rw.id(), rw.costPoints(), rw.name());
        }
    }

    private void tiers() {
        out.println("Tiers:");
        for (Tier t : service.tierThresholds()) {
            out.printf("  %-10s min spend %d%n", t.name(), t.minSpend());
        }
    }

    private static Map<String, String> parseFlags(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            int eq = arg.indexOf('=');
            if (!arg.startsWith("--") || eq < 0) {
                throw new UsageException("Bad argument: " + arg + " (expected --key=value)");
            }
            flags.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        return flags;
    }

    private static String require(Map<String, String> flags, String key) {
        String value = flags.get(key);
        if (value == null || value.isBlank()) {
            throw new UsageException("--" + key + " is required");
        }
        return value;
    }

    private static LocalDate optionalDate(Map<String, String> flags, String key) {
        String value = flags.get(key);
        return value == null ? null : LocalDate.parse(value);
    }

    private void printUsage(PrintStream s) {
        s.println("""
                Usage: points <command> [--flags]

                  earn    --user=<id> --purchase-id=<id> --amount=<dollars> [--date=YYYY-MM-DD]
                  balance --user=<id> [--as-of=YYYY-MM-DD]
                  redeem  --user=<id> --reward=<id> [--date=YYYY-MM-DD]
                  refund  --user=<id> --purchase-id=<id> [--date=YYYY-MM-DD]
                  rewards
                  tiers

                Talks to the database directly (LOYALTY_DB_URL, default loyalty.db).""");
    }

    /** Signals a malformed command line (vs. a valid command that fails for a business reason). */
    private static class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }
}
