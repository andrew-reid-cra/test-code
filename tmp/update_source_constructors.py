from pathlib import Path
path = Path(r"src/test/java/ca/gc/cra/radar/capture/pcap/PcapFilePacketSourceTest.java")
text = path.read_text()
text = text.replace('    PcapFilePacketSource source = new PcapFilePacketSource(fixture, "tcp port 80", 65535);\n', '    PcapFilePacketSource source = new PcapFilePacketSource(\n        fixture, "tcp port 80", 65535, () -> new StubPcap(fixture));\n')
text = text.replace('    PcapFilePacketSource source = new PcapFilePacketSource(fixture, "tcp port 443", 65535);\n', '    PcapFilePacketSource source = new PcapFilePacketSource(\n        fixture, "tcp port 443", 65535, () -> new StubPcap(fixture));\n')
path.write_text(text)
