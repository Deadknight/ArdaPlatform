# ARDA Platform Bootstrap — RN-3B

Package: `com.github.deadknight.ardaplatform`

Custom RFCOMM UUID:

```text
e43cd6de-4bb2-49fe-8d43-ecace91038cc
```

Build and install:

```bash
chmod +x gradlew build-debug-mac.sh install-aaos-debug-mac.sh
./build-debug-mac.sh
./install-aaos-debug-mac.sh
```

On AAOS:

```text
Grant Nearby
Discoverable
Start server
```

Expected markers:

```text
ARDA_RN3B_AAOS_RFCOMM_LISTEN=PASS
ARDA_RN3B_AAOS_HELLO_RX=PASS
ARDA_RN3B_AAOS_HELLO_ACK_TX=PASS
ARDA_RN3B_AAOS_PONG_TX=PASS
```
