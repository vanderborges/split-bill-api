package com.splitbill.application.usecase;

import com.splitbill.application.dto.MonthlyBalanceResponse;
import com.splitbill.application.dto.PaymentSuggestionResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSuggestionTest {

    private final UUID receiver = UUID.randomUUID();
    private final UUID ana = UUID.randomUUID();
    private final UUID beto = UUID.randomUUID();
    private final UUID carla = UUID.randomUUID();
    private final UUID davi = UUID.randomUUID();

    @Test
    void receiverForwardsToEveryOtherCreditor() {
        // Recebedor +10, dois outros credores (+30, +20), dois devedores.
        List<PaymentSuggestionResponse> suggestions = MonthlyReportUseCase.routeThroughReceiver(List.of(
                balance(receiver, "Rec", "10.00"),
                balance(ana, "Ana", "30.00"),
                balance(beto, "Beto", "20.00"),
                balance(carla, "Carla", "-35.00"),
                balance(davi, "Davi", "-25.00")
        ), receiver, "Rec");

        assertThat(suggestions).extracting(s -> s.fromNickname() + ">" + s.toNickname() + ":" + s.amount())
                .containsExactlyInAnyOrder(
                        "Carla>Rec:35.00",
                        "Davi>Rec:25.00",
                        "Rec>Ana:30.00",
                        "Rec>Beto:20.00");
        assertBalanced(suggestions, receiver, "10.00");
    }

    @Test
    void indebtedReceiverPaysCreditorsInFull() {
        List<PaymentSuggestionResponse> suggestions = MonthlyReportUseCase.routeThroughReceiver(List.of(
                balance(receiver, "Rec", "-15.00"),
                balance(ana, "Ana", "40.00"),
                balance(beto, "Beto", "5.00"),
                balance(carla, "Carla", "-30.00")
        ), receiver, "Rec");

        assertThat(suggestions).extracting(s -> s.fromNickname() + ">" + s.toNickname() + ":" + s.amount())
                .containsExactlyInAnyOrder(
                        "Carla>Rec:30.00",
                        "Rec>Ana:40.00",
                        "Rec>Beto:5.00");
        assertBalanced(suggestions, receiver, "-15.00");
    }

    private static void assertBalanced(List<PaymentSuggestionResponse> suggestions, UUID user, String expected) {
        BigDecimal net = suggestions.stream()
                .map(s -> s.toUserId().equals(user) ? s.amount()
                        : s.fromUserId().equals(user) ? s.amount().negate() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Saldo positivo = recebe no total exatamente o que tem a receber.
        assertThat(net).isEqualByComparingTo(expected);
    }

    private static MonthlyBalanceResponse balance(UUID id, String nickname, String value) {
        return new MonthlyBalanceResponse(id, nickname, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(value));
    }
}
