package com.pos.client.service;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import com.solab.iso8583.parse.ConfigParser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;


import java.io.IOException;
import java.text.ParseException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class PosTerminalService {

    @Value("${van.server.ip:127.0.0.1}")
    private String vanServerIp;

    // VAN 서버에 정의된 포트와 동일하게 세팅 (7777)
    private final int vanServerPort = 7777;
    // 응답이 이 시간 안에 안 오면 연결이 죽은 것으로 보고 끊는다.
    private static final int READ_TIMEOUT_MS = 5_000;

    private MessageFactory<IsoMessage> messageFactory;

    // 결제마다 새로 맺지 않고 재사용하는 커넥션. 끊어지면 null로 돌려 다음 호출에서 재연결한다.
    private Socket socket;
    private OutputStream out;
    private InputStream in;

    @PostConstruct
    public void init() {
        try {
            // POS 클라이언트도 VAN 서버와 동일한 파싱 규칙(j8583.xml)을 사용
            messageFactory = ConfigParser.createDefault();
            messageFactory.setCharacterEncoding("UTF-8"); // VAN과 반드시 같은 charset을 써야 한다(전문 규격의 일부)
        } catch (Exception e) {
            log.error("POS 클라이언트 MessageFactory 초기화 실패! j8583.xml 확인 필요", e);
        }
    }

    /**
     * ISO 8583 바이너리 전문을 조립하여 VAN 서버로 전송합니다.
     *
     * 커넥션은 결제마다 새로 맺지 않고 재사용한다. 다만 재사용하는 커넥션은 상대(VAN)가 이미
     * 끊어놨을 수 있고(재기동·유휴 종료), 그건 보내보기 전에는 알 수 없다. 그래서 재사용한
     * 커넥션에서 실패하면 새 커넥션으로 한 번만 다시 보낸다.
     *
     * 이때 STAN을 새로 만들지 않는 것이 중요하다. STAN은 멱등키의 원점이므로 같은 값으로 보내야
     * 첫 전문이 이미 카드사까지 처리된 경우에도 이중결제가 되지 않고 저장된 결과가 돌아온다.
     *
     * 물리적으로 한 단말은 한 번에 한 건만 처리하므로 synchronized로 직렬화한다.
     */
    public synchronized String sendPaymentRequest(String cardNumber, String amount, String merchantId) {
        // STAN은 이 결제 건에 대해 한 번만 생성한다 (재시도해도 동일한 값 유지)
        String stan = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        boolean reusedConnection = isConnectionAlive();

        try {
            return exchange(cardNumber, amount, merchantId, stan);
        } catch (Exception first) {
            closeConnection();

            if (!reusedConnection) {
                // 방금 새로 맺은 커넥션에서 실패한 것이므로 다시 보내도 결과가 같다
                log.error("[POS-Service] VAN 서버 통신 에러", first);
                throw new RuntimeException("VAN 서버와 통신할 수 없습니다. (포트 7777 확인)");
            }

            log.warn("[POS-Service] 재사용 커넥션이 끊겨 있었습니다. 같은 STAN({})으로 1회 재전송합니다. 사유: {}",
                    stan, first.toString());
            try {
                return exchange(cardNumber, amount, merchantId, stan);
            } catch (Exception retry) {
                closeConnection();
                log.error("[POS-Service] 재전송도 실패", retry);
                throw new RuntimeException("VAN 서버와 통신할 수 없습니다. (포트 7777 확인)");
            }
        }
    }

    /** 전문 1건을 조립해 보내고 응답을 받아 해석한다. 실패는 예외로 던져 호출자가 재전송을 판단하게 한다. */
    private String exchange(String cardNumber, String amount, String merchantId, String stan) throws IOException, ParseException {
        ensureConnected();

        // 1. 요청 전문(MTI: 0200) 객체 생성
        IsoMessage isoReq = messageFactory.newMessage(0x0200);

        // 2. 필드 세팅 (VAN 서버의 getObjectValue 인덱스에 정확히 매핑)
        isoReq.setValue(2, cardNumber, IsoType.LLVAR, 16);

        // VAN 서버가 "Long.parseLong(amountStr) / 100" 으로 처리하므로,
        // 클라이언트는 원본 금액에 "00"을 붙여서(x100) 처리
        String formattedAmount = amount + "00";
        isoReq.setValue(4, formattedAmount, IsoType.NUMERIC, 12);

        // STAN(field 11): 거래 추적번호. 멱등키의 원점으로 사용된다.
        isoReq.setValue(11, stan, IsoType.NUMERIC, 6);

        isoReq.setValue(42, merchantId, IsoType.ALPHA, 15);

        // 3. Byte 배열로 직렬화하여 TCP 전송
        byte[] requestBytes = isoReq.writeData();
        out.write(requestBytes);
        out.flush();
        log.info("[POS-Service] VAN으로 전문 전송 완료 ({} bytes, STAN={})", requestBytes.length, stan);

        // 4. 응답(MTI: 0210) 수신 대기
        byte[] responseBuffer = new byte[2048];
        int bytesRead = in.read(responseBuffer);
        if (bytesRead <= 0) {
            // 상대가 응답 없이 연결을 닫았다 → 재전송 판단은 호출자에게 맡긴다
            throw new IOException("VAN이 응답 없이 연결을 종료했습니다");
        }

        // 5. 응답 Byte 배열을 ISO 객체로 파싱
        IsoMessage isoRes = messageFactory.parseMessage(responseBuffer, 0);

        // VAN 서버가 응답코드를 세팅한 39번, 120번 필드 추출
        String responseCode = isoRes.getObjectValue(39);
        String fdsMessage = isoRes.getObjectValue(120);

        // STAN(11번)은 요청과 응답을 짝지으라고 있는 필드다. 커넥션을 재사용하는 구조에서는
        // 직전 거래의 응답을 잘못 읽을 여지가 있으므로 실제로 대조한다.
        String echoedStan = isoRes.getObjectValue(11);
        if (echoedStan != null && !echoedStan.equals(stan)) {
            throw new IOException("응답 STAN 불일치 (보낸 값=" + stan + ", 받은 값=" + echoedStan + ")");
        }

        log.info("[POS-Service] 응답 수신 완료 - 응답코드: {}, 메시지: {}", responseCode, fdsMessage);

        // 6. 결과 분기 처리: 00이면 성공, 그 외 모든 코드는 사유+코드를 그대로 표시
        if ("00".equals(responseCode)) {
            return "SUCCESS";
        }
        return (fdsMessage != null && !fdsMessage.isEmpty())
                ? fdsMessage + " (응답코드: " + responseCode + ")"
                : "결제 거절 (응답코드: " + responseCode + ")";
    }

    private boolean isConnectionAlive() {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    /**
     * 커넥션이 없거나 끊어져 있으면 새로 연결한다. 살아있으면 아무것도 하지 않고 재사용.
     */
    private void ensureConnected() throws IOException {
        if (!isConnectionAlive()) {
            log.info("🔥 [POS-Service] VAN 서버({}:{})로 새 커넥션 연결...", vanServerIp, vanServerPort);
            socket = new Socket(vanServerIp, vanServerPort);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            out = socket.getOutputStream();
            in = socket.getInputStream();
        }
    }

    @PreDestroy
    public void closeConnection() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // 이미 끊어진 소켓을 닫는 중 나는 에러는 무시해도 된다
        } finally {
            socket = null;
            out = null;
            in = null;
        }
    }
}
