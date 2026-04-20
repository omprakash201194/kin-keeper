package com.ogautam.kinkeeper.model;

/**
 * Where the bill record came from. Lets us distinguish agent-parsed SMS
 * records from manually-entered ones for trust / "did I get a duplicate"
 * checks later.
 */
public enum BillSource {
    MANUAL,
    SMS,
    EMAIL,
    CHAT
}
