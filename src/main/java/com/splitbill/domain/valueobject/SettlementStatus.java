package com.splitbill.domain.valueobject;

public enum SettlementStatus {
    PENDING,
    PAID,
    PAID_TO_ADMIN,
    RECEIVED_FROM_ADMIN,
    CONFIRMED,
    // Quem devia tocou em "Pagar" > "Confirmar pagamento": falta o admin
    // confirmar (vira PAID) ou recusar (volta pra PENDING).
    INFORMED
}
