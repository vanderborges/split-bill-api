package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.RegisterDeviceRequest;
import com.splitbill.application.dto.UnregisterDeviceRequest;
import com.splitbill.application.usecase.DeviceTokenUseCase;
import com.splitbill.infrastructure.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/devices")
public class DeviceController {

    private final DeviceTokenUseCase devices;
    private final CurrentUser currentUser;

    public DeviceController(DeviceTokenUseCase devices, CurrentUser currentUser) {
        this.devices = devices;
        this.currentUser = currentUser;
    }

    @PostMapping
    public void register(@Valid @RequestBody RegisterDeviceRequest request) {
        devices.register(currentUser.id(), request.token(), request.platform());
    }

    // POST em vez de DELETE: o token vai no corpo.
    @PostMapping("/unregister")
    public void unregister(@Valid @RequestBody UnregisterDeviceRequest request) {
        devices.unregister(currentUser.id(), request.token());
    }
}
