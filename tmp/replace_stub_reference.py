from pathlib import Path
path = Path(r"src/test/java/ca/gc/cra/radar/capture/pcap/PcapFilePacketSourceTest.java")
text = path.read_text()
text = text.replace('(() -> new StubPcap(fixture))', '(() -> PcapFixtures.offlineStub(fixture))')
path.write_text(text)
