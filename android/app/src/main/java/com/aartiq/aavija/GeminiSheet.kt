package com.aartiq.aavija

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Google Gemini AI Assistant Dialog for Aavija.
 *
 * Provides real-time device intelligence, status reporting (Mac, Windows, Android),
 * interactive unpairing, deep link handling, and natural language explanations.
 */
@Composable
fun GeminiSheet(
    assistant: GeminiAssistant,
    onDismiss: () -> Unit,
    initialQuery: String? = null,
) {
    val colors = LocalAavijaColors.current
    val isDark = colors.isDark

    val messages by assistant.messages.collectAsState()
    val isLoading by assistant.isLoading.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showApiKeyInput by remember { mutableStateOf(false) }
    var apiKeyText by remember { mutableStateOf(assistant.apiKey ?: "") }
    val listState = rememberLazyListState()

    // Automatically trigger initial query if launched from a deep link:
    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) {
            assistant.ask(initialQuery)
        }
    }

    // Scroll to bottom on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxSize(0.90f)
                    .imePadding()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .shadow(32.dp, RoundedCornerShape(32.dp)),
                shape = RoundedCornerShape(32.dp),
                color = if (isDark) Color(0xFF070B14) else Color(0xFFFFFFFF),
                border = BorderStroke(
                    1.2.dp,
                    if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                ) {
                    // Header Bar
                    GeminiHeader(
                        isDark = isDark,
                        onClose = onDismiss,
                        onToggleApiKey = { showApiKeyInput = !showApiKeyInput },
                        hasApiKey = !assistant.apiKey.isNullOrBlank(),
                    )

                    Spacer(Modifier.height(12.dp))

                    // API Key Config Panel (Collapsible)
                    AnimatedVisibility(visible = showApiKeyInput) {
                        ApiKeyConfigCard(
                            apiKey = apiKeyText,
                            onApiKeyChange = { apiKeyText = it },
                            onSave = {
                                assistant.apiKey = apiKeyText
                                showApiKeyInput = false
                            },
                            isDark = isDark,
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    // Chat messages list
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            GeminiMessageBubble(
                                message = msg,
                                isDark = isDark,
                                onActionClick = { action ->
                                    assistant.executeAction(action)
                                },
                            )
                        }

                        if (isLoading) {
                            item {
                                Row(
                                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF818CF8),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Gemini is analyzing connected devices...",
                                        fontSize = 12.sp,
                                        color = Color(0xFFA5B4FC),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Quick Suggestion Chips
                    QuickSuggestionBar(
                        onSelectPrompt = { prompt ->
                            assistant.ask(prompt)
                        },
                        isDark = isDark,
                    )

                    Spacer(Modifier.height(10.dp))

                    // Input Bar
                    GeminiInputBar(
                        value = inputText,
                        onValueChange = { inputText = it },
                        onSend = {
                            if (inputText.isNotBlank()) {
                                assistant.ask(inputText)
                                inputText = ""
                            }
                        },
                        isDark = isDark,
                        isLoading = isLoading,
                    )
                }
            }
        }
    }
}

@Composable
private fun GeminiHeader(
    isDark: Boolean,
    onClose: () -> Unit,
    onToggleApiKey: () -> Unit,
    hasApiKey: Boolean,
) {
    val colors = LocalAavijaColors.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Gemini Gradient Icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF38BDF8), Color(0xFF818CF8), Color(0xFFC084FC))
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("✨", fontSize = 16.sp)
            }

            Spacer(Modifier.width(10.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Google Gemini",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF6366F1).copy(alpha = 0.2f),
                    ) {
                        Text(
                            text = "AI ASSISTANT",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFA5B4FC),
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    text = "Device Monitor • Unpair Control • TLS 1.3",
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // API Key Button
            Surface(
                shape = CircleShape,
                color = if (hasApiKey) Color(0xFF10B981).copy(alpha = 0.15f) else (if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000)),
                border = BorderStroke(1.dp, if (hasApiKey) Color(0xFF10B981).copy(alpha = 0.4f) else Color.Transparent),
                modifier = Modifier
                    .size(32.dp)
                    .clickable(onClick = onToggleApiKey),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("🔑", fontSize = 13.sp)
                }
            }

            Spacer(Modifier.width(8.dp))

            // Close Button
            Surface(
                shape = CircleShape,
                color = if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000),
                modifier = Modifier
                    .size(32.dp)
                    .clickable(onClick = onClose),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("✕", fontSize = 14.sp, color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun GeminiMessageBubble(
    message: GeminiMessage,
    isDark: Boolean,
    onActionClick: (GeminiAction) -> Unit,
) {
    val colors = LocalAavijaColors.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start,
    ) {
        if (!message.isUser) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp, start = 4.dp),
            ) {
                Text(
                    text = "Gemini",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFA5B4FC),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (message.isUser) 20.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 20.dp,
            ),
            color = if (message.isUser) {
                Color(0xFF4F46E5)
            } else {
                if (isDark) Color(0xFF111827) else Color(0xFFF1F5F9)
            },
            border = if (!message.isUser) {
                BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f))
            } else null,
            modifier = Modifier.fillMaxWidth(if (message.isUser) 0.85f else 0.98f),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = message.text,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = if (message.isUser) Color.White else colors.textPrimary,
                )

                // Render Action Buttons if available
                if (message.quickActions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        message.quickActions.forEach { action ->
                            val isUnpair = action.actionType == GeminiActionType.UNPAIR_MAC ||
                                action.actionType == GeminiActionType.UNPAIR_WINDOWS ||
                                action.actionType == GeminiActionType.UNPAIR_ALL

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isUnpair) Color(0xFFEF4444).copy(alpha = 0.15f) else Color(0xFF38BDF8).copy(alpha = 0.15f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isUnpair) Color(0xFFEF4444).copy(alpha = 0.45f) else Color(0xFF38BDF8).copy(alpha = 0.4f),
                                ),
                                modifier = Modifier.clickable { onActionClick(action) },
                            ) {
                                Text(
                                    text = action.title,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isUnpair) Color(0xFFFF6B6B) else Color(0xFF38BDF8),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickSuggestionBar(
    onSelectPrompt: (String) -> Unit,
    isDark: Boolean,
) {
    val suggestions = listOf(
        "Which devices are connected?",
        "Unpair my Mac",
        "Unpair Windows PC",
        "How do gestures work?",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        suggestions.take(2).forEach { text ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isDark) Color(0xFF1E293B).copy(alpha = 0.7f) else Color(0xFFE2E8F0),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelectPrompt(text) },
            ) {
                Text(
                    text = text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF334155),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun GeminiInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isDark: Boolean,
    isLoading: Boolean,
) {
    val colors = LocalAavijaColors.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = "Ask Gemini (e.g. \"Which devices are connected?\")...",
                    fontSize = 12.sp,
                    color = colors.textTertiary,
                )
            },
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF818CF8),
                unfocusedBorderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.12f),
                focusedContainerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                unfocusedContainerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            maxLines = 3,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.width(8.dp))

        Surface(
            shape = CircleShape,
            color = if (value.isNotBlank() && !isLoading) Color(0xFF4F46E5) else (if (isDark) Color(0xFF1F2937) else Color(0xFFCBD5E1)),
            modifier = Modifier
                .size(44.dp)
                .clickable(enabled = value.isNotBlank() && !isLoading, onClick = onSend),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("➤", fontSize = 16.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun ApiKeyConfigCard(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    isDark: Boolean,
) {
    val colors = LocalAavijaColors.current

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isDark) Color(0xFF0F172A) else Color(0xFFF1F5F9),
        border = BorderStroke(1.dp, Color(0xFF818CF8).copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "🔑 Google Gemini API Key (Optional)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Aavija works 100% offline with its local engine. Enter a Gemini API Key to enable cloud-powered conversational reasoning.",
                fontSize = 10.5.sp,
                color = colors.textSecondary,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    placeholder = { Text("AIzaSy...", fontSize = 11.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF4F46E5),
                    modifier = Modifier.clickable(onClick = onSave),
                ) {
                    Text(
                        text = "Save",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
