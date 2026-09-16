package com.deepon.smartdocs.user.dto;

public record ChangePasswordRequest(String currentPassword, String newPassword) {
}
