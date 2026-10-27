-- CreateTable
CREATE TABLE "device_events" (
    "id" BIGSERIAL NOT NULL,
    "agent_id" INTEGER NOT NULL,
    "type" TEXT NOT NULL,
    "at" TIMESTAMPTZ NOT NULL,
    "detail" JSONB,
    "received_at" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "device_events_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "device_events_agent_id_at_idx" ON "device_events"("agent_id", "at");

-- CreateIndex
CREATE UNIQUE INDEX "device_events_agent_id_type_at_key" ON "device_events"("agent_id", "type", "at");

-- AddForeignKey
ALTER TABLE "device_events" ADD CONSTRAINT "device_events_agent_id_fkey" FOREIGN KEY ("agent_id") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
