package com.splitbill.domain.valueobject;

public enum EventStatus {
    OPEN,
    /** Despesas congeladas, aguardando confirmação de pagamento de todos antes de fechar. */
    SETTLING,
    CLOSED
}
