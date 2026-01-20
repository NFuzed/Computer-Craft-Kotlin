local msg = ...
local bit = bit32

local function rol(n, b)
    return bit.bor(
            bit.lshift(n, b),
            bit.rshift(n, 32 - b)
    )
end

local function toUint32(x)
    return bit.band(x, 0xffffffff)
end

local H0 = 0x67452301
local H1 = 0xefcdab89
local H2 = 0x98badcfe
local H3 = 0x10325476
local H4 = 0xc3d2e1f0

local ml = #msg * 8
msg = msg .. string.char(0x80)

while (#msg % 64) ~= 56 do
    msg = msg .. "\0"
end

msg = msg ..
        string.char(
                0, 0, 0, 0,
                bit.rshift(ml, 24),
                bit.band(bit.rshift(ml, 16), 0xff),
                bit.band(bit.rshift(ml, 8), 0xff),
                bit.band(ml, 0xff)
        )

for i = 1, #msg, 64 do
    local w = {}

    for j = 0, 15 do
        local k = i + j * 4
        w[j] = bit.lshift(string.byte(msg, k), 24) +
                bit.lshift(string.byte(msg, k + 1), 16) +
                bit.lshift(string.byte(msg, k + 2), 8) +
                string.byte(msg, k + 3)
    end

    for j = 16, 79 do
        w[j] = rol(
                bit.bxor(w[j - 3], w[j - 8], w[j - 14], w[j - 16]),
                1
        )
    end

    local a, b, c, d, e = H0, H1, H2, H3, H4

    for j = 0, 79 do
        local f, k
        if j < 20 then
            f = bit.bor(bit.band(b, c), bit.band(bit.bnot(b), d))
            k = 0x5a827999
        elseif j < 40 then
            f = bit.bxor(b, c, d)
            k = 0x6ed9eba1
        elseif j < 60 then
            f = bit.bor(
                    bit.band(b, c),
                    bit.band(b, d),
                    bit.band(c, d)
            )
            k = 0x8f1bbcdc
        else
            f = bit.bxor(b, c, d)
            k = 0xca62c1d6
        end

        local temp = toUint32(
                rol(a, 5) + f + e + k + w[j]
        )
        e = d
        d = c
        c = rol(b, 30)
        b = a
        a = temp
    end

    H0 = toUint32(H0 + a)
    H1 = toUint32(H1 + b)
    H2 = toUint32(H2 + c)
    H3 = toUint32(H3 + d)
    H4 = toUint32(H4 + e)
end

return string.format(
        "%08x%08x%08x%08x%08x",
        H0, H1, H2, H3, H4
)

