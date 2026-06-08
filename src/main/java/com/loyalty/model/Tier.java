package com.loyalty.model;

/** A loyalty tier and the minimum rolling-12-month spend (in points) required to reach it. */
public record Tier(String name, int minSpend) {
}
