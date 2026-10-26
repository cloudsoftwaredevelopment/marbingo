-- CreateEnum
CREATE TYPE "LocationSource" AS ENUM ('gps', 'manual');

-- AlterTable
ALTER TABLE "customers" ADD COLUMN     "lat" DOUBLE PRECISION,
ADD COLUMN     "lng" DOUBLE PRECISION,
ADD COLUMN     "located_at" TIMESTAMPTZ,
ADD COLUMN     "located_by" INTEGER,
ADD COLUMN     "location_accuracy" DOUBLE PRECISION,
ADD COLUMN     "location_source" "LocationSource";

-- AlterTable
ALTER TABLE "users" ADD COLUMN     "tracking_consent_at" TIMESTAMPTZ;

-- CreateTable
CREATE TABLE "location_points" (
    "id" BIGSERIAL NOT NULL,
    "agent_id" INTEGER NOT NULL,
    "recorded_at" TIMESTAMPTZ NOT NULL,
    "lat" DOUBLE PRECISION NOT NULL,
    "lng" DOUBLE PRECISION NOT NULL,
    "accuracy" DOUBLE PRECISION,
    "speed" DOUBLE PRECISION,
    "bearing" DOUBLE PRECISION,
    "battery" INTEGER,
    "received_at" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "location_points_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "agent_stops" (
    "id" SERIAL NOT NULL,
    "agent_id" INTEGER NOT NULL,
    "day" TEXT NOT NULL,
    "started_at" TIMESTAMPTZ NOT NULL,
    "ended_at" TIMESTAMPTZ NOT NULL,
    "duration_s" INTEGER NOT NULL,
    "lat" DOUBLE PRECISION NOT NULL,
    "lng" DOUBLE PRECISION NOT NULL,
    "customer_id" INTEGER,
    "point_count" INTEGER NOT NULL,

    CONSTRAINT "agent_stops_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "track_cache" (
    "agent_id" INTEGER NOT NULL,
    "day" TEXT NOT NULL,
    "point_count" INTEGER NOT NULL,
    "geometry" JSONB NOT NULL,
    "snapped" BOOLEAN NOT NULL,
    "distance_m" INTEGER NOT NULL,
    "computed_at" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "track_cache_pkey" PRIMARY KEY ("agent_id","day")
);

-- CreateIndex
CREATE UNIQUE INDEX "location_points_agent_id_recorded_at_key" ON "location_points"("agent_id", "recorded_at");

-- CreateIndex
CREATE INDEX "agent_stops_agent_id_day_idx" ON "agent_stops"("agent_id", "day");

-- CreateIndex
CREATE INDEX "agent_stops_customer_id_started_at_idx" ON "agent_stops"("customer_id", "started_at");

-- CreateIndex
CREATE UNIQUE INDEX "agent_stops_agent_id_started_at_key" ON "agent_stops"("agent_id", "started_at");

-- AddForeignKey
ALTER TABLE "customers" ADD CONSTRAINT "customers_located_by_fkey" FOREIGN KEY ("located_by") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "location_points" ADD CONSTRAINT "location_points_agent_id_fkey" FOREIGN KEY ("agent_id") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "agent_stops" ADD CONSTRAINT "agent_stops_agent_id_fkey" FOREIGN KEY ("agent_id") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "agent_stops" ADD CONSTRAINT "agent_stops_customer_id_fkey" FOREIGN KEY ("customer_id") REFERENCES "customers"("id") ON DELETE SET NULL ON UPDATE CASCADE;
