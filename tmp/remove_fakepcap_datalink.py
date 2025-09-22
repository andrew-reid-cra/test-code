from pathlib import Path
path = Path(r"src/test/java/ca/gc/cra/radar/infrastructure/capture/PcapPacketSourceTest.java")
text = path.read_text()
text = text.replace('\n\n\n\n    @Override\n    public int dataLink() {\n      return 1;\n    }\n', '\n\n')
path.write_text(text)
