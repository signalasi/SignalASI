################################################################################
#
# signalasi-runtime-launcher
#
################################################################################

SIGNALASI_RUNTIME_LAUNCHER_VERSION = 1.0.0
SIGNALASI_RUNTIME_LAUNCHER_SITE = $(BR2_EXTERNAL_SIGNALASI_PATH)/package/signalasi-runtime-launcher/src
SIGNALASI_RUNTIME_LAUNCHER_SITE_METHOD = local
SIGNALASI_RUNTIME_LAUNCHER_LICENSE = Apache-2.0

define SIGNALASI_RUNTIME_LAUNCHER_BUILD_CMDS
	$(TARGET_CC) $(TARGET_CFLAGS) $(TARGET_LDFLAGS) \
		-Wall -Wextra -Werror -D_FORTIFY_SOURCE=2 -fstack-protector-strong \
		-o $(@D)/signalasi-runtime-launcher $(@D)/signalasi-runtime-launcher.c
endef

define SIGNALASI_RUNTIME_LAUNCHER_INSTALL_TARGET_CMDS
	$(INSTALL) -D -m 0755 $(@D)/signalasi-runtime-launcher \
		$(TARGET_DIR)/usr/libexec/signalasi-runtime-launcher
endef

$(eval $(generic-package))
