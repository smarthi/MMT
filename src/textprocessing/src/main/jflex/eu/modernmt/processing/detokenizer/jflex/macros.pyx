CJK        = [\u1100-\u11FF\u2E80-\uA4CF\uA840-\uA87F\uAC00-\uD7AF\uF900-\uFAFF\uFE30-\uFE4F\uFF65-\uFFDC]
_          = " "
Letter     = !(![:letter:]|{CJK})
Currency   = [\$€¢¥£]

quot       = \"
amp        = \&
apos       = \'
lt         = <
gt         = >