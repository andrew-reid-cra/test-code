
# 3-Node High-Throughput Architecture for 40 Gbps Packet Pipeline

This document describes a production-grade 3-node architecture (`Capture → Assemble → Poster`) capable of sustaining **40 Gbps @ 256 B (~19.5 Mpps)** with **zero drops**, along with detailed tuning recommendations.

---

##  High-Level Flow

```
[ Capture Node ] ---> [ Kafka / NATS ] ---> [ Assemble Node ] ---> [ Kafka / FS ] ---> [ Poster Node ]
```
- **Capture:** Ingests packets, timestamps, batches, and publishes to a message bus.
- **Assemble:** Performs flow reassembly, protocol detection, and feature extraction.
- **Poster:** Serializes, compresses, and persists data to disk or produces to downstream systems.

---

##  Hardware Profiles

### 1. Capture Node
| Component | Recommended |
|----------|------------|
| CPU | 32C/64T (EPYC 9354/9374F or Xeon SPR) ≥3.0GHz |
| RAM | 128 GiB |
| NIC | 1×100 GbE (≥32 RX queues, RSS, flow steering) |
| Disk | Minimal (OS + logs) |
| Fabric | 100 GbE to broker + assemble |

**Notes:** Capture is latency-sensitive. Pin threads, use kernel bypass if possible (AF_XDP/DPDK).

---

### 2. Assemble Node
| Component | Recommended |
|----------|------------|
| CPU | 48–64C (dual-socket or fat single), ≥3.0 GHz |
| RAM | 256 GiB |
| NIC | 1×100 GbE |
| Disk | 2× NVMe (for spill/flow tables) |

**Notes:** Flow reassembly is CPU- and memory-bound. Use NUMA pinning and large tables.

---

### 3. Poster Node
| Component | Recommended |
|----------|------------|
| CPU | 24–32C |
| RAM | 128 GiB |
| NIC | 1×100 GbE |
| Disk | 4× NVMe (≥7 GB/s sustained) |

**Notes:** Optimize for I/O throughput. Use async writes or high-throughput Kafka producer settings.

---

##  Transport and Topics

### Ingress: `Capture → Assemble`
- **Topic:** `packets.raw`
- **Partitions:** 32 (match RX queues)
- **Key:** 5-tuple hash for flow stickiness

Producer config:
```
linger.ms=5–10
batch.size=1–2MB
compression=lz4|zstd
acks=1
```

### Egress: `Assemble → Poster`
- **Topic:** `flows.pairs`
- **Partitions:** 48–64 (match assemble workers)

---

##  Example Configurations

### Capture Node

```bash
java -XX:+AlwaysPreTouch -Xms16g -Xmx16g -jar RADAR.jar capture   iface=ens5   snaplen=96   bufmb=2048   timeout=50   out=kafka://broker:9092/packets.raw   producer.linger.ms=10   producer.batch.size=2097152   producer.compression=lz4   metricsExporter=otlp   otelEndpoint=http://otel-collector:4317
```

### Assemble Node

```bash
java -XX:+AlwaysPreTouch -Xms64g -Xmx64g -jar RADAR.jar assemble   in=kafka://broker:9092/packets.raw   groupId=assemble-1   workers=48   workerAffinity=on   flowTable.maxEntries=15000000   flowTable.idleTimeout=120s   out=kafka://broker:9092/flows.pairs   producer.linger.ms=10   producer.batch.size=2097152   producer.compression=zstd   persistQueueType=ARRAY   persistQueueCapacity=131072   metricsExporter=otlp   otelEndpoint=http://otel-collector:4317
```

### Poster Node

```bash
java -XX:+AlwaysPreTouch -Xms24g -Xmx24g -jar RADAR.jar poster   in=kafka://broker:9092/flows.pairs   groupId=poster-1   persistWorkers=24   persistQueueType=ARRAY   persistQueueCapacity=131072   out=/data/flows   file.rollInterval=60s   file.maxBytes=134217728   fsync=off   metricsExporter=otlp   otelEndpoint=http://otel-collector:4317
```

---

##  Performance Targets

| Stage | Target | Metric |
|-------|--------|--------|
| Capture | ≤ 610 kpps per RX queue | `capture.rx.drops` = 0 |
| Assemble | ≤ 406 kpps per worker | `persist.enqueue.dropped` = 0 |
| Poster | ≥ 5 GB/s write or Kafka ingest | `persist.queue.depth` < 80% |

---

##  Monitoring & Alerts

- **Queue Depth:** < 80% sustained
- **Drops:** Always 0 (`enqueue.dropped`, `rx.drops`)
- **Latency:** p99 persist < 2× timeout
- **Kafka:** Partition lag < 1s, ISR stable

Alert thresholds:
- Queue depth > 80% for >60s
- Any drop counter > 0
- p99 latency > 2× expected window

---

##  Key Success Factors

1. **RSS & IRQ Pinning:** ≥32 queues, NUMA-aware thread placement.  
2. **Short Snaplen:** Reduces copy/parse overhead.  
3. **Large Buffers:** High `bufmb`, large `persistQueueCapacity`.  
4. **Partitioning:** Match Kafka partitions to workers.  
5. **NVMe / Broker Bandwidth:** ≥5 GB/s sustained.  
6. **Monitoring:** Proactive alerting on queue depth and drops.

---

###  Throughput Sanity Check

- 40 Gbps @ 256 B ≈ 19.5 Mpps total
- Capture (32 RX queues): ~610 kpps/queue
- Assemble (48 workers): ~406 kpps/worker

These are achievable on the recommended hardware with the provided tuning.

---

##  Final Notes

This architecture is designed to scale horizontally. If you need >40 Gbps, scale **Capture** and **Assemble** nodes in parallel and shard traffic by IP range, VLAN, or 5‑tuple hash. Kafka provides natural fan-out for downstream consumers.

