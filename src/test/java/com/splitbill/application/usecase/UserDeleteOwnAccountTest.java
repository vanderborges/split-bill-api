package com.splitbill.application.usecase;

import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.EventStatus;
import com.splitbill.infrastructure.persistence.entity.DeviceTokenJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.DeviceTokenJpaRepository;
import com.splitbill.infrastructure.persistence.repository.ExpenseJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import com.splitbill.infrastructure.persistence.repository.NotificationJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** "Excluir conta" do Perfil: exigencia da App Store (5.1.1(v)) e do Google Play. */
class UserDeleteOwnAccountTest {

    private final UserJpaRepository users = mock(UserJpaRepository.class);
    private final ExpenseJpaRepository expenses = mock(ExpenseJpaRepository.class);
    private final DeviceTokenJpaRepository deviceTokens = mock(DeviceTokenJpaRepository.class);
    private final NotificationJpaRepository notifications = mock(NotificationJpaRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final UserUseCase useCase = new UserUseCase(
            users, expenses, mock(GroupMemberJpaRepository.class), encoder, deviceTokens, notifications);

    private final UserJpaEntity user = new UserJpaEntity();

    @BeforeEach
    void setUp() {
        user.setId(UUID.randomUUID());
        user.setFullName("Ana Souza");
        user.setNickname("Ana");
        user.setEmail("ana@exemplo.com");
        user.setPhone("11999999999");
        user.setPixKey("ana@pix");
        user.setPasswordHash("hash-antigo");
        user.setActive(true);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(encoder.encode(any())).thenReturn("hash-aleatorio");
    }

    @Test
    void commonUserDeletesOwnAccountAndPersonalDataGoesAway() {
        DeviceTokenJpaEntity phone = new DeviceTokenJpaEntity();
        when(deviceTokens.findByUserId(user.getId())).thenReturn(List.of(phone));

        useCase.deleteOwnAccount(user.getId());

        assertThat(user.isActive()).isFalse();
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getEmail()).endsWith("@dividiai.invalid").doesNotContain("ana@exemplo.com");
        assertThat(user.getFullName()).isEqualTo("Ana");
        assertThat(user.getPhone()).isEmpty();
        assertThat(user.getPixKey()).isEmpty();
        assertThat(user.getPasswordHash()).isEqualTo("hash-aleatorio");
        verify(deviceTokens).deleteAll(List.of(phone));
        verify(notifications).deleteByRecipientId(user.getId());
    }

    @Test
    void openEventBlocksWithExplanation() {
        when(expenses.existsByUserInvolvementAndEventStatusNot(user.getId(), EventStatus.CLOSED)).thenReturn(true);

        assertThatThrownBy(() -> useCase.deleteOwnAccount(user.getId()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("evento em aberto");
        assertThat(user.isActive()).isTrue();
        assertThat(user.getEmail()).isEqualTo("ana@exemplo.com");
        verify(notifications, never()).deleteByRecipientId(any());
    }
}
