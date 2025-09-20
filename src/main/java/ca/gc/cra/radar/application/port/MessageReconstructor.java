package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.net.ByteStream;
import java.util.List;

public interface MessageReconstructor {
  void onStart();

  List<MessageEvent> onBytes(ByteStream slice) throws Exception;

  void onClose();
}


