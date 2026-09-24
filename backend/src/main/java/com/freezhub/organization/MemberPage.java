package com.freezhub.organization;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * One page of the members list (FZ-212).
 *
 * <p>Its own shape rather than Spring's {@code Page}, whose JSON is an implementation detail
 * of the framework and has changed between versions. {@code totalItems} is what lets the
 * screen say how many people there are, not only how many it is showing.
 */
public record MemberPage(
        List<MemberResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages) {

    static MemberPage from(Page<User> page) {
        return new MemberPage(page.getContent().stream().map(MemberResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
