package com.company.shop.common.i18n;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

@Service
public class MessageService {

    private final MessageSource messageSource;

    public MessageService(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String getMessage(final String key, final Object... args) {
        final Locale locale = LocaleContextHolder.getLocale();
        try {
            return messageSource.getMessage(key, args, locale);
        } catch (NoSuchMessageException ex) {
            throw new IllegalArgumentException("Missing i18n message key: " + key + " for locale: " + locale, ex);
        }
    }

    public String getMessageOrDefault(final String key, final String defaultMessage, final Object... args) {
        return messageSource.getMessage(key, args, defaultMessage, LocaleContextHolder.getLocale());
    }
}
