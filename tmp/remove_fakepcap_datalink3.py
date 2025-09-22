from pathlib import Path
path = Path(r"src/test/java/ca/gc/cra/radar/infrastructure/capture/PcapPacketSourceTest.java")
text = path.read_text()
# Remove dataLink method inside FakePcap
text = text.replace('\n    @Override\n    public int dataLink() {\n      return 1;\n    }\n', '\n', 1)
path.write_text(text)
