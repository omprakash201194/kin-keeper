package com.ogautam.kinkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Points at a subject of a document or reminder: a family member, an external
 * contact, or one of the asset types. Documents may have any number of these
 * (e.g. a car insurance policy links to both the policyholder member and the
 * vehicle asset).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkRef {
    private LinkType type;
    private String id;
}
