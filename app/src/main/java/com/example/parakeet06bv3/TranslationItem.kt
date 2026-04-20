package com.example.parakeet06bv3

data class TranslationItem(
    var englishText: String,
    var koreanText: String,
    var isFinal: Boolean = false,    // STT 확정 여부 (마이크 입력이 끝났는가?)
    var isStreaming: Boolean = false // 백엔드 SSE 수신 여부 (타이핑 중인가?)
)