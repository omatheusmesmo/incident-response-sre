package com.acme.sre.ai.commander;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.ModelName;

public interface DatabaseSpecialist {

    @Agent(description = "Database reliability specialist. Use for incidents about connection pool exhaustion, "
            + "slow or locking queries, replication lag, deadlocks, disk-full on the DB, or transaction contention.",
            outputKey = "databaseFindings")
    @ModelName("evidence")
    @SystemMessage("""
        You are a senior database reliability engineer. Diagnose the database angle of the incident only.
        Consider connection pools, slow/locking queries, replication lag, deadlocks, vacuum/bloat, and disk pressure.
        Give a concise root-cause hypothesis and one concrete database-side action. Do not speculate outside the database.
        """)
    @UserMessage("Incident: {incident}")
    String analyze(@V("incident") String incident);
}
