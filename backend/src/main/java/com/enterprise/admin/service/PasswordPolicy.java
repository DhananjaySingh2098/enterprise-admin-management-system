package com.enterprise.admin.service;

import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

/**
 * Rules for new passwords. Minimum 12 characters; maximum 72 UTF-8 bytes because BCrypt ignores anything
 * beyond that, which would silently weaken long passphrases.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BYTES = 72;
    public static final String DESCRIPTION =
            "at least " + MIN_LENGTH + " characters and at most " + MAX_BYTES + " bytes";

    public boolean isAcceptable(String password) {
        return password != null
                && !password.isBlank()
                && password.codePointCount(0, password.length()) >= MIN_LENGTH
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
