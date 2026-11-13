package com.spaza.finance;

import java.util.Map;
import java.util.Optional;

/** The only view of mandates the agent receives: read-only snapshots, no write methods. */
public interface MandateReader {
  Optional<Map<String,Object>> snapshot(String ownerId);
}
