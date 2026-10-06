package cn.dreamingfish.updater.player;

import java.util.List;

/**
 * An optional group as the player window shows it.
 *
 * @param enabled  the effective state: the player's choice, or the published default
 * @param explicit whether the player made a choice for this group
 * @param members  display names of the group's files
 */
record OptionalGroupView(String id, String title, String description, boolean defaultInstall,
                         boolean enabled, boolean explicit, List<String> members) {
    OptionalGroupView {
        members = members == null ? List.of() : List.copyOf(members);
    }
}
