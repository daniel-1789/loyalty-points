package com.loyalty.model;

/** A catalog entry: what a customer can redeem and what it costs in points. */
public record Reward(String id, String name, int costPoints) {
}
