api
 ├─ depends on config (composition)
 └─ depends on application

config
 └─ depends on application & infrastructure (wire adapters)

application
 ├─ depends on domain
 ├─ uses application.port.*
 └─ has util helpers (pure logic depending only on domain)

domain
 └─ (no dependencies)

infrastructure
 ├─ implements application.port.*
 ├─ depends on domain (DTOs/models)
 └─ may depend on external libs (pcap, IO)

tests
 └─ can depend on api/application/domain/infrastructure (per slice) without reverse deps
