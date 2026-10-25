package com.splitbill.application.usecase;

import com.splitbill.domain.exception.DomainException;
import com.splitbill.infrastructure.persistence.entity.DeviceTokenJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.DeviceTokenJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Aparelhos que recebem push. O app registra no login e remove no "Sair". */
@Service
public class DeviceTokenUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenUseCase.class);

    private final DeviceTokenJpaRepository devices;
    private final UserJpaRepository users;

    public DeviceTokenUseCase(DeviceTokenJpaRepository devices, UserJpaRepository users) {
        this.devices = devices;
        this.users = users;
    }

    @Transactional
    public void register(UUID userId, String token, String platform) {
        UserJpaEntity user = users.findById(userId)
                .orElseThrow(() -> new DomainException("User not found"));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // Mesmo aparelho logado em outra conta: o token passa pra conta nova
        // (senao a pessoa anterior continuaria recebendo push aqui).
        DeviceTokenJpaEntity device = devices.findByToken(token).orElseGet(() -> {
            DeviceTokenJpaEntity created = new DeviceTokenJpaEntity();
            created.setId(UUID.randomUUID());
            created.setToken(token);
            created.setCreatedAt(now);
            return created;
        });
        device.setUser(user);
        device.setPlatform(platform);
        device.setUpdatedAt(now);
        devices.save(device);
        log.info("Push: aparelho {} registrado para o usuario {}.", platform, userId);
    }

    @Transactional
    public void unregister(UUID userId, String token) {
        devices.findByToken(token)
                .filter(device -> device.getUser().getId().equals(userId))
                .ifPresent(devices::delete);
    }
}
