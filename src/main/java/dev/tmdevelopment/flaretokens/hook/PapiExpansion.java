package dev.tmdevelopment.flaretokens.hook;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.data.Storage;
import dev.tmdevelopment.flaretokens.util.Num;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Locale;

/**
 * %flaretokens_tokens%              balance                 (3.x: %flaretokens_get_tokens%)
 * %flaretokens_tokens_formatted%    1.2k, 3.4M ...           (3.x: %flaretokens_get_formatted_tokens%)
 * %flaretokens_tokens_commas%       1,234,567                (3.x: %flaretokens_get_commas_tokens%)
 * %flaretokens_top_&lt;n&gt;_name%        name at position n of the top
 * %flaretokens_top_&lt;n&gt;_tokens%      balance at position n (also _tokens_formatted, _tokens_commas)
 * %flaretokens_position%            position of the player in the top ("-" when not in it)
 * %flaretokens_accounts%            players with an account
 * %flaretokens_sale%                discount of the timed sale (0 = none), %flaretokens_sale_time% time left
 */
public final class PapiExpansion extends PlaceholderExpansion {

    private final FlareTokens plugin;

    public PapiExpansion(FlareTokens plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "flaretokens";
    }

    @Override
    public String getAuthor() {
        return "TMDevelopment";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String p = params.toLowerCase(Locale.ROOT);
        if (p.startsWith("top_")) {
            String[] parts = p.split("_", 3);
            if (parts.length < 3) return "";
            int pos;
            try {
                pos = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return "";
            }
            List<Storage.Account> top = plugin.tokens().top();
            boolean exists = pos >= 1 && pos <= top.size();
            Storage.Account a = exists ? top.get(pos - 1) : null;
            switch (parts[2]) {
                case "name":
                    return a == null || a.name == null ? "-" : a.name;
                case "tokens":
                    return a == null ? "0" : String.valueOf(a.tokens);
                case "tokens_formatted":
                    return a == null ? "0" : Num.shortFormat(a.tokens);
                case "tokens_commas":
                    return a == null ? "0" : Num.commas(a.tokens);
                default:
                    return "";
            }
        }
        if (p.equals("accounts")) return String.valueOf(plugin.tokens().accounts());
        if (p.equals("sale") || p.equals("sale_percent")) return String.valueOf(plugin.sales().percentNow());
        if (p.equals("sale_time")) return plugin.sales().timeLeft();
        if (player == null) return "";
        long t = plugin.tokens().balanceForDisplay(player.getUniqueId());
        switch (p) {
            case "tokens":
            case "get_tokens":
            case "balance":
                return String.valueOf(t);
            case "tokens_formatted":
            case "get_formatted_tokens":
            case "formatted":
                return Num.shortFormat(t);
            case "tokens_commas":
            case "get_commas_tokens":
            case "commas":
                return Num.commas(t);
            case "position": {
                List<Storage.Account> top = plugin.tokens().top();
                for (int i = 0; i < top.size(); i++) if (top.get(i).uuid.equals(player.getUniqueId())) return String.valueOf(i + 1);
                return "-";
            }
            default:
                return null;
        }
    }
}
