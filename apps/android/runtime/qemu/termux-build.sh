TERMUX_PKG_HOMEPAGE=https://www.qemu.org
TERMUX_PKG_DESCRIPTION="SignalASI private AArch64 system emulator"
TERMUX_PKG_LICENSE="GPL-2.0-only"
TERMUX_PKG_MAINTAINER="SignalASI"
TERMUX_PKG_VERSION="10.2.1"
TERMUX_PKG_SRCURL="https://download.qemu.org/qemu-${TERMUX_PKG_VERSION}.tar.xz"
TERMUX_PKG_SHA256=a3717477d8e2c84d630bfffbc20f6cd3293eb45aa1e6dac6d0cc27689991c9e1
TERMUX_PKG_DEPENDS="dtc, glib, libandroid-shmem, libpixman, zlib"
TERMUX_PKG_BUILD_IN_SRC=true

termux_step_pre_configure() {
	rm -rf "$TERMUX_PKG_BUILDDIR/_lib" "$TERMUX_PKG_BUILDDIR/_setjmp-aarch64"
	mkdir -p "$TERMUX_PKG_BUILDDIR/_lib" "$TERMUX_PKG_BUILDDIR/_setjmp-aarch64/private"
	pushd "$TERMUX_PKG_BUILDDIR/_setjmp-aarch64" >/dev/null
	local source_file
	for source_file in "$TERMUX_PKG_BUILDER_DIR"/setjmp-aarch64/{setjmp.S,private-*.h}; do
		local target_file
		target_file="$(basename "$source_file")"
		cp "$source_file" "${target_file/-//}"
	done
	test -f private/bionic_asm.h
	test -f private/bionic_constants.h
	"$CC" $CFLAGS $CPPFLAGS -I. setjmp.S -c
	"$AR" cru "$TERMUX_PKG_BUILDDIR/_lib/libandroid-setjmp.a" setjmp.o
	popd >/dev/null
	LDFLAGS+=" -L$TERMUX_PKG_BUILDDIR/_lib -l:libandroid-setjmp.a -landroid-shmem -llog"
}

termux_step_configure() {
	termux_setup_ninja
	CFLAGS+=" $CPPFLAGS"
	CXXFLAGS+=" $CPPFLAGS"

	./configure \
		--prefix="$TERMUX_PREFIX" \
		--cross-prefix="${TERMUX_HOST_PLATFORM}-" \
		--host-cc=gcc \
		--cc="$CC" \
		--cxx="$CXX" \
		--objcc="$CC" \
		--disable-stack-protector \
		--target-list=aarch64-softmmu \
		--enable-tcg \
		--disable-kvm \
		--enable-coroutine-pool \
		--enable-trace-backends=nop \
		--enable-virtfs \
		--enable-fdt=system \
		--disable-docs \
		--disable-tools \
		--disable-modules \
		--disable-guest-agent \
		--disable-sdl \
		--disable-sdl-image \
		--disable-gtk \
		--disable-vte \
		--disable-curses \
		--disable-vnc \
		--disable-vnc-sasl \
		--disable-vnc-jpeg \
		--disable-png \
		--disable-opengl \
		--disable-virglrenderer \
		--disable-spice \
		--disable-slirp \
		--disable-curl \
		--disable-gnutls \
		--disable-nettle \
		--disable-gcrypt \
		--disable-libssh \
		--disable-libnfs \
		--disable-lzo \
		--disable-snappy \
		--disable-bzip2 \
		--disable-zstd \
		--disable-lzfse \
		--disable-libiscsi \
		--disable-rbd \
		--disable-libusb \
		--disable-usb-redir \
		--disable-vhost-net \
		--disable-vhost-user \
		--disable-vhost-user-blk-server \
		--disable-xen \
		--disable-xen-pci-passthrough \
		--disable-hvf \
		--disable-whpx \
		--disable-seccomp \
		--disable-linux-aio \
		--disable-linux-io-uring \
		--disable-iconv \
		--audio-drv-list=
}

termux_step_post_make_install() {
	test -x "$TERMUX_PREFIX/bin/qemu-system-aarch64"
}
