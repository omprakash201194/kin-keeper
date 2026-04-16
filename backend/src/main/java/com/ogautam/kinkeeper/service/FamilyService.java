package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.Firestore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class FamilyService {

    private static final String FAMILIES_COLLECTION = "families";
    private static final String MEMBERS_COLLECTION = "members";

    private final Firestore firestore;

    public FamilyService(Firestore firestore) {
        this.firestore = firestore;
    }

    // TODO: createFamily, getFamily, addMember, listMembers, inviteUser
}
