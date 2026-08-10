package io.github.chzarles.paperloom.config;

import io.github.chzarles.paperloom.model.RegistrationMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.auth")
public class AppAuthProperties {

    private final Registration registration = new Registration();
    private final Guest guest = new Guest();

    public Registration getRegistration() {
        return registration;
    }

    public Guest getGuest() {
        return guest;
    }

    public static class Registration {
        private RegistrationMode mode = RegistrationMode.INVITE_ONLY;
        private boolean inviteRequired = true;

        public RegistrationMode getMode() {
            return mode;
        }

        public void setMode(RegistrationMode mode) {
            this.mode = mode;
        }

        public boolean isInviteRequired() {
            return inviteRequired;
        }

        public void setInviteRequired(boolean inviteRequired) {
            this.inviteRequired = inviteRequired;
        }
    }

    public static class Guest {
        private int dailyLoginAttemptLimit = 100;

        public int getDailyLoginAttemptLimit() {
            return dailyLoginAttemptLimit;
        }

        public void setDailyLoginAttemptLimit(int dailyLoginAttemptLimit) {
            this.dailyLoginAttemptLimit = dailyLoginAttemptLimit;
        }
    }
}
