# 💳 POS Client (가맹점 POS 단말기 에뮬레이터)

## 📖 개요
`pos-client`는 가맹점에 설치된 실제 POS 단말기의 동작을 모사(Emulate)하는 애플리케이션입니다. 

웹 기반의 UI(`pos-terminal.html`)를 통해 결제 정보를 입력받고, 이를 금융권 국제 표준 통신 포맷인 **ISO 8583** 형태의 바이너리 전문으로 변환한 뒤, **TCP 소켓 통신**을 통해 VAN 서버(`localhost:7777`)로 결제 승인을 요청합니다.

---

## 🌐 통신 아키텍처 및 흐름 (Communication Flow)

1. **사용자 입력:** 웹 UI에서 카드번호, 결제금액, 가맹점 ID 입력 (`HTTP POST /api/pos/payment`)
2. **전문 변환 (Marshalling):** JSON 요청 데이터를 **ISO 8583 (MTI 0200)** 포맷의 바이트 배열로 변환
3. **TCP 소켓 통신:** VAN 서버와 TCP 커넥션을 맺고 전문 전송 (길이 헤더 2바이트 포함)
4. **응답 수신 (Unmarshalling):** VAN 서버로부터 수신된 ISO 8583 (MTI 0210) 전문을 파싱
5. **결과 반환:** 응답 코드(Field 39)와 상세 메시지(Field 120)를 추출하여 UI에 표시

---

## 📜 ISO 8583 메시지 포맷의 이해


ISO 8583은 전 세계 금융 시스템에서 신용카드 및 직불카드 거래 정보를 교환하기 위해 사용하는 국제 표준 규격입니다. <br>
본 프로젝트에서는 `j8583` 라이브러리를 활용하여 이 포맷을 구현했습니다.

전문은 크게 세 부분으로 구성됩니다.

* **MTI (Message Type Indicator - 4자리 숫자):** 메시지의 목적을 정의합니다.
  * `0200`: 결제 승인 요청 (Request)
  * `0210`: 결제 승인 응답 (Response)
* **Bitmap (비트맵):** 메시지 내에 어떤 데이터 필드(Data Elements)가 포함되어 있는지 나타내는 16진수 맵입니다.
* **Data Elements (데이터 필드):** 실제 결제 정보가 담기는 공간으로, 각 필드마다 엄격한 타입과 길이 규칙이 존재합니다.

---

## 🛠️ 전문 매핑 명세 (Message Mapping Specification)

본 프로젝트의 `j8583.xml` 및 `PosTerminalService.java`에 정의된 데이터 필드 규격은 다음과 같습니다.

### 📤 1. 결제 요청 전문 (Request - MTI 0200)
POS 단말기에서 VAN 서버로 전송하는 결제 요청 데이터입니다.

| 필드 번호 | 필드명 | 데이터 타입 | 길이 | 설명 | 구현부 추출/세팅 로직 |
|:---:|---|:---:|:---:|---|---|
| **2** | Primary Account Number (PAN) | `LLVAR` | 가변(최대 19) | 카드 번호 | `isoMessage.setValue(2, cardNum, ...)` |
| **4** | Amount, Transaction | `NUMERIC` | 12 | 결제 금액 | `isoMessage.setValue(4, amount * 100, ...)` |
| **11** | STAN (System Trace Audit Number)| `NUMERIC` | 6 | 거래 추적 고유 번호 | 난수 6자리 자동 생성 |
| **42** | Card Acceptor ID (Merchant ID) | `ALPHA` | 15 | 가맹점 고유 ID | `isoMessage.setValue(42, merchantId, ...)` |

### 📥 2. 결제 응답 전문 (Response - MTI 0210)
VAN 서버에서 결제 처리 후 POS로 반환하는 응답 데이터입니다.

| 필드 번호 | 필드명 | 데이터 타입 | 길이 | 설명 | 구현부 추출 로직 |
|:---:|---|:---:|:---:|---|---|
| **39** | Response Code | `ALPHA` | 2 | 처리 결과 코드 | `isoRes.getObjectValue(39)` ("00"=승인, 그 외 거절) |
| **120** | Reserved for Private Use | `LLLVAR` | 가변(최대 200) | 상세 메시지 | `isoRes.getObjectValue(120)` |

---

## 💻 핵심 구현 상세 (Key Implementations)

### 1. j8583 라이브러리를 활용한 전문 조립
`j8583.xml` 설정 파일을 기반으로 `MessageFactory`를 초기화하여, <br>
개발자가 비트맵이나 바이트 패딩을 직접 계산할 필요 없이 객체 지향적으로 전문을 다룰 수 있게 구현했습니다.

```java
// PosTerminalService.java 중 전문 생성 로직
IsoMessage isoMessage = messageFactory.newMessage(0x0200); // MTI 0200 생성
isoMessage.setValue(2, cardNum, IsoType.LLVAR, 16);
isoMessage.setValue(4, amount * 100, IsoType.NUMERIC, 12); // ISO 포맷에 맞춘 금액 패딩 처리
isoMessage.setValue(11, stan, IsoType.NUMERIC, 6);
isoMessage.setValue(42, merchantId, IsoType.ALPHA, 15);
```

### 2. TCP 소켓 통신 및 2-Byte 길이 헤더 처리
단순한 HTTP 통신이 아닌, 금융권 망에서 사용하는 전통적인 **TCP Socket 통신**을 구현했습니다. <br>
스트림 충돌을 막기 위해 메시지의 맨 앞단에 2바이트의 길이 데이터(Length Header)를 부착하여 전송하고, <br>
수신할 때도 길이를 먼저 읽어 들여 정확한 크기만큼의 페이로드를 읽도록 설계되었습니다.

```java
// PosTerminalService.java 중 TCP 송신 로직
byte[] messageBytes = isoMessage.writeData();
int length = messageBytes.length;

// [2-Byte 길이 헤더] + [ISO 8583 메시지 본문]
byte[] lengthBytes = new byte[2];
lengthBytes[0] = (byte) (length >> 8);   // 상위 바이트 추출
lengthBytes[1] = (byte) (length & 0xFF); // 하위 바이트 추출

out.write(lengthBytes);
out.write(messageBytes);
out.flush();
```
