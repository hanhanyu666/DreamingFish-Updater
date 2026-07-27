package cn.dreamingfish.updater.management;

import java.util.regex.Pattern;

final class GlobMatcher {
    private GlobMatcher() {
    }

    static Pattern compile(String glob) {
        if (glob == null || glob.isBlank() || glob.contains("\\")) {
            throw new ManagementException("Glob must use forward slashes and cannot be empty: " + glob);
        }
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doubleStar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                if (doubleStar) {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;
                        regex.append("(?:.*/)?");
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                if (".[]{}()+-^$|".indexOf(c) >= 0) {
                    regex.append('\\');
                }
                regex.append(c);
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }
}
