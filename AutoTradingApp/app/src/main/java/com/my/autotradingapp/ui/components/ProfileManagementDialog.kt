package com.my.autotradingapp.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.autotradingapp.data.UserProfile
import com.my.autotradingapp.ui.TradingViewModel

@Composable
fun ProfileManagementDialog(
    viewModel: TradingViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("TradingApp", android.content.Context.MODE_PRIVATE) }
    val profiles by viewModel.profiles.collectAsState()
    val currentUserId by viewModel.userId.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<UserProfile?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("가족 및 계정 프로필 관리", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "원클릭으로 다른 가족 계정으로 전환하거나, 새로운 프로필을 추가/관리할 수 있습니다.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(profiles) { profile ->
                        val isSelected = profile.id == currentUserId
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!isSelected) {
                                        viewModel.setUserId(profile.id)
                                        prefs.edit().putString("LastUsedUserId", profile.id).apply()
                                        Toast.makeText(context, "${profile.name} 계정으로 전환되었습니다.", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(profile.icon, fontSize = 24.sp)
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = profile.name,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp
                                            )
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                                    Text("현재 선택됨", color = Color.White, fontSize = 10.sp)
                                                }
                                            }
                                        }
                                        Text(
                                            text = "ID: ${profile.id}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (!isSelected) {
                                    IconButton(
                                        onClick = { profileToDelete = profile }
                                    ) {
                                        Text("🗑️", fontSize = 16.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("➕ 새 가족 프로필 추가")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기")
            }
        }
    )

    // 새 프로필 추가 서브 다이얼로그
    if (showAddDialog) {
        var newNameInput by remember { mutableStateOf("") }
        var newIdInput by remember { mutableStateOf("") }
        var isChecking by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { if (!isChecking) showAddDialog = false },
            title = { Text("새 프로필 추가", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("추가할 가족 또는 본인의 별칭과 고유 ID를 입력하세요.", fontSize = 13.sp)

                    OutlinedTextField(
                        value = newNameInput,
                        onValueChange = { 
                            newNameInput = it
                            errorMessage = ""
                        },
                        label = { Text("프로필 이름 / 별칭 (예: 와이프, 나래, 둘째)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = newIdInput,
                        onValueChange = { 
                            newIdInput = it.trim()
                            errorMessage = ""
                        },
                        label = { Text("고유 ID (Firebase 노드명, 예: narae)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isChecking,
                    onClick = {
                        val cleanName = newNameInput.trim()
                        val cleanId = newIdInput.trim()
                        if (cleanName.isEmpty()) {
                            errorMessage = "프로필 이름을 입력해주세요."
                            return@Button
                        }
                        if (cleanId.isEmpty()) {
                            errorMessage = "고유 ID를 입력해주세요."
                            return@Button
                        }

                        isChecking = true
                        viewModel.checkUserIdExists(cleanId) { exists ->
                            isChecking = false
                            if (exists) {
                                errorMessage = "🚫 이미 사용 중인 사용자 ID입니다. 다른 이름을 입력해주세요."
                            } else {
                                // 신규 생성
                                viewModel.setProfileName(cleanId, cleanName)
                                viewModel.setUserId(cleanId)
                                prefs.edit().putString("LastUsedUserId", cleanId).apply()
                                Toast.makeText(context, "'${cleanName}' 프로필이 생성되었습니다. 증권사 계좌를 등록해주세요.", Toast.LENGTH_LONG).show()
                                showAddDialog = false
                                onDismiss()
                            }
                        }
                    }
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("생성")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isChecking,
                    onClick = { showAddDialog = false }
                ) {
                    Text("취소")
                }
            }
        )
    }

    // 프로필 삭제 확인 다이얼로그
    profileToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("프로필 삭제 확인", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
            text = {
                Text("'${target.name} (ID: ${target.id})' 프로필과 해당 데이터베이스 노드를 완전히 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.")
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val idToDelete = target.id
                        viewModel.deleteProfile(idToDelete) { success, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            if (success) {
                                profileToDelete = null
                            }
                        }
                    }
                ) {
                    Text("삭제", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) {
                    Text("취소")
                }
            }
        )
    }
}
