package com.pos.client.service;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import com.solab.iso8583.parse.ConfigParser;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;


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

    private MessageFactory<IsoMessage> messageFactory;

    @PostConstruct
    public void init() {
        try {
            // POS 클라이언트도 VAN 서버와 동일한 파싱 규칙(j8583.xml)을 사용
            messageFactory = ConfigParser.createDefault();
        } catch (Exception e) {
            log.error("POS 클라이언트 MessageFactory 초기화 실패! j8583.xml 확인 필요", e);
        }
    }

    /**
     * ISO 8583 바이너리 전문을 조립하여 VAN 서버로 전송합니다.
     */
    public String sendPaymentRequest(String cardNumber, String amount, String merchantId) {
        log.info("🔥 [POS-Service] VAN 서버({}:{})로 ISO 8583 결제 요청 시작...", vanServerIp, vanServerPort);

        try (Socket socket = new Socket(vanServerIp, vanServerPort);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            // 1. 요청 전문(MTI: 0200) 객체 생성
            IsoMessage isoReq = messageFactory.newMessage(0x0200);

            // 2. 필드 세팅 (VAN 서버의 getObjectValue 인덱스에 정확히 매핑)
            isoReq.setValue(2, cardNumber, IsoType.LLVAR, 16);

            // VAN 서버가 "Long.parseLong(amountStr) / 100" 으로 처리하므로,
            // 클라이언트는 원본 금액에 "00"을 붙여서(x100) 처리
            String formattedAmount = amount + "00";
            isoReq.setValue(4, formattedAmount, IsoType.NUMERIC, 12);

            // STAN(field 11): 거래 추적번호. 멱등키의 원점으로 사용된다.
            String stan = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
            isoReq.setValue(11, stan, IsoType.NUMERIC, 6);

            isoReq.setValue(42, merchantId, IsoType.ALPHA, 15);

            // 3. Byte 배열로 직렬화하여 TCP 전송
            byte[] requestBytes = isoReq.writeData();
            out.write(requestBytes);
            out.flush();
            log.info("[POS-Service] VAN으로 전문 전송 완료 ({} bytes)", requestBytes.length);

            // 4. 응답(MTI: 0210) 수신 대기
            byte[] responseBuffer = new byte[2048];
            int bytesRead = in.read(responseBuffer);

            if (bytesRead > 0) {
                // 5. 응답 Byte 배열을 ISO 객체로 파싱
                IsoMessage isoRes = messageFactory.parseMessage(responseBuffer, 0);

                // VAN 서버가 응답코드를 세팅한 39번, 120번 필드 추출
                String responseCode = isoRes.getObjectValue(39);
                String fdsMessage = isoRes.getObjectValue(120);
                log.info("[POS-Service] 응답 수신 완료 - 응답코드: {}, 메시지: {}", responseCode, fdsMessage);

                // 6. 결과 분기 처리: 00이면 성공, 그 외 모든 코드는 사유+코드를 그대로 표시
                if ("00".equals(responseCode)) {
                    return "SUCCESS";
                }
                return (fdsMessage != null && !fdsMessage.isEmpty())
                        ? fdsMessage + " (응답코드: " + responseCode + ")"
                        : "결제 거절 (응답코드: " + responseCode + ")";
            } else {
                return "VAN 서버로부터 응답을 받지 못했습니다.";
            }

        } catch (Exception e) {
            log.error("[POS-Service] VAN 서버 통신 에러", e);
            throw new RuntimeException("VAN 서버와 통신할 수 없습니다. (포트 7777 확인)");
        }
    }
}