package com.pos.client.controller;

import com.pos.client.service.PosTerminalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Slf4j
@Controller
@RequiredArgsConstructor
public class PosController {

    private final PosTerminalService posTerminalService;

    // POS 단말기 메인 화면 띄우기
    @GetMapping("/")
    public String showPosScreen() {
        return "pos-terminal";
    }

    // 화면에서 '결제' 버튼을 눌렀을 때 처리
    @PostMapping("/pay")
    public String processPayment(
            @RequestParam String cardNumber,
            @RequestParam String amount,
            @RequestParam String merchantId,
            Model model) {

        log.info("[POS 화면] 결제 요청 수신 - 금액: {}원", amount);

        try {
            String result = posTerminalService.sendPaymentRequest(cardNumber, amount, merchantId);

            if ("SUCCESS".equals(result)) {
                // 성공 시
                model.addAttribute("successMsg", "✅ 결제가 정상적으로 승인되었습니다!");
            } else {
                // 거절(51) 시 - FDS 차단 사유 등
                model.addAttribute("errorMsg", "❌ 결제 거절: " + result);
            }

        } catch (Exception e) {
            // 서버가 꺼져있거나 통신 에러가 났을 때
            model.addAttribute("errorMsg", "⚠️ VAN사 통신 에러: " + e.getMessage());
        }

        model.addAttribute("cardNumber", cardNumber);
        model.addAttribute("amount", amount);

        return "pos-terminal";
    }
}