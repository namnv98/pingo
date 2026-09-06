<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
*table of contents*

- [file-upload](#file-upload)
    - [Installation](#installation)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

# file-upload

Lua script for file management

## Installation

### 1. Use OpenResty Package Manager (OPM) to install these libraries

**lua-resty-http**: Lua HTTP client cosocket driver for OpenResty / ngx_lua.
https://github.com/ledgetech/lua-resty-http

```bash
opm install ledgetech/lua-resty-http
```

**lua-resty-jit-uuid**: A pure LuaJIT (no dependencies) UUID library tuned for performance
https://github.com/thibaultcha/lua-resty-jit-uuid

```bash
opm install thibaultcha/lua-resty-jit-uuid
```

**lua-fs-module**: Lua file helper.
https://github.com/xiedacon/lua-fs-module

```bash
opm install xiedacon/lua-fs-module
```

**lua-resty-mime-sniff**: detect file mime
https://github.com/spacewander/lua-resty-mime-sniff

```bash
opm install spacewander/lua-resty-mime-sniff
```

**lua-resty-string**: String utilities and common hash functions for ngx_lua and LuaJIT
https://github.com/openresty/lua-resty-string

```bash
opm install openresty/lua-resty-string
```

**lua-resty-nettle**: LuaJIT FFI bindings for Nettle (a low-level cryptographic library)
https://github.com/bungle/lua-resty-nettle

```bash
opm install bungle/lua-resty-nettle
```

### 2.Use package manager for Lua modules (Luarocks) to install these libraries

**brimworks/lua-zip**: For zip files
https://github.com/brimworks/lua-zip

```bash
luarocks install lua-zip  ## must install cmake and libzip-devel before
```

**luafilesystem**: Reimplement luafilesystem via LuaJIT FFI.
https://github.com/spacewander/luafilesystem

```bash
luarocks install luafilesystem
```

**leafo/magick**: Lua bindings to ImageMagick's MagicWand or GraphicsMagick's Wand for LuaJIT using
FFI.
https://github.com/leafo/magick

```bash
luarocks install magick
```

**keplerproject/md5**: Dependency library for lua-resty-string for md5
https://github.com/keplerproject/md5

```bash
luarocks install md5
```
