from pathlib import Path
path = Path(r"src/test/java/ca/gc/cra/radar/capture/pcap/PcapFilePacketSourceTest.java")
text = path.read_text()
text = text.replace('import ca.gc.cra.radar.infrastructure.capture.libpcap.PcapHandle;\n', 'import ca.gc.cra.radar.infrastructure.capture.libpcap.PcapHandle;\nimport ca.gc.cra.radar.testutil.PcapFixtures;\n')
path.write_text(text)
