package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.FilePolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class RuleSet {
    private final List<CompiledRule> rules;

    RuleSet(ProjectRules source) {
        rules = new ArrayList<>();
        for (FileRule rule : source.rules()) {
            if (rule.action() == null) {
                throw new ManagementException("File rule action is missing for " + rule.glob());
            }
            rules.add(new CompiledRule(GlobMatcher.compile(rule.glob()), rule.action()));
        }
    }

    Decision decide(String path) {
        RuleAction action = RuleAction.ENFORCED;
        for (CompiledRule rule : rules) {
            if (rule.pattern().matcher(path).matches()) {
                action = rule.action();
            }
        }
        return action == RuleAction.EXCLUDE
                ? Decision.excludedDecision()
                : Decision.managedDecision(action.toFilePolicy());
    }

    record Decision(boolean excluded, FilePolicy policy) {
        static Decision excludedDecision() {
            return new Decision(true, null);
        }

        static Decision managedDecision(FilePolicy policy) {
            return new Decision(false, policy);
        }
    }

    private record CompiledRule(Pattern pattern, RuleAction action) {
    }
}
