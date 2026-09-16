package com.deepon.smartdocs.security;

import com.deepon.smartdocs.common.Sha256;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code ip_hash = sha256(salt || ip)}. The raw IP never reaches the
 * database (design doc section 8.2). The salt is a rotatable secret: a
 * rotation invalidates historical correlation across the salt boundary,
 * which is the point.
 */
@Component
public class IpHasher {

    private final String salt;

    public IpHasher(@Value("${smartdocs.security.ip-hash-salt}") String salt) {
        this.salt = salt;
    }

    public String hash(String ip) {
        return Sha256.hex(salt + (ip == null ? "" : ip));
    }
}
