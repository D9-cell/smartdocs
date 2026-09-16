package com.deepon.smartdocs.user.service.impl;

import com.deepon.smartdocs.user.entity.AppUser;
import com.deepon.smartdocs.user.exception.SessionInvalidException;
import com.deepon.smartdocs.user.repository.UserRepository;
import com.deepon.smartdocs.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public AppUser getActiveById(UUID userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .filter(AppUser::isActive)
                .orElseThrow(SessionInvalidException::new);
    }
}
