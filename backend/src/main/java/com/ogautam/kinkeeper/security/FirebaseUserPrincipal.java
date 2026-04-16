package com.ogautam.kinkeeper.security;

import java.security.Principal;

public record FirebaseUserPrincipal(
        String uid,
        String email,
        String name,
        String picture
) implements Principal {

    @Override
    public String getName() {
        return uid;
    }
}
