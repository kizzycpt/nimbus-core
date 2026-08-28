package com.nimbus.api.session;

/**
 * A resolved session, flattened to plain values.
 *
 * The filter that consumes this runs outside any transaction (open-in-view is
 * off), so handing it a managed entity would leave it holding a lazy proxy it
 * cannot initialise. Everything the request needs is read up front instead.
 */
public record ActiveSession(Long id, Long userId, String username) {}
