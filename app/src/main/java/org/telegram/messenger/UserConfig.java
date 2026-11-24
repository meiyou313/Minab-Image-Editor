package org.telegram.messenger;

public class UserConfig {
    public static int selectedAccount = 0;

    public static UserConfig getInstance(int num) {
        return INSTANCE;
    }

    private static final UserConfig INSTANCE = new UserConfig();
}
