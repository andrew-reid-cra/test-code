from pathlib import Path
cfg_path = Path(r"src/main/java/ca/gc/cra/radar/config/CompositionRoot.java")
text = cfg_path.read_text()
old = "  private PacketSource newPacketSource() {\n    return new PcapPacketSource(\n        captureConfig.iface(),\n        captureConfig.snaplen(),\n        captureConfig.promiscuous(),\n        captureConfig.timeoutMillis(),\n        captureConfig.bufferBytes(),\n        captureConfig.immediate(),\n        captureConfig.filter());\n  }\n"
if old not in text:
    raise SystemExit('old packet source block not found')
new = "  private PacketSource newPacketSource() {\n    if (captureConfig.pcapFile() != null) {\n      return new PcapFilePacketSource(\n          captureConfig.pcapFile(), captureConfig.filter(), captureConfig.snaplen());\n    }\n    return new PcapPacketSource(\n        captureConfig.iface(),\n        captureConfig.snaplen(),\n        captureConfig.promiscuous(),\n        captureConfig.timeoutMillis(),\n        captureConfig.bufferBytes(),\n        captureConfig.immediate(),\n        captureConfig.filter());\n  }\n"
text = text.replace(old, new)
cfg_path.write_text(text)
