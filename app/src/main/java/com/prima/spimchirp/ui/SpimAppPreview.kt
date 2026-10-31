package com.prima.spimchirp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Modern App", showBackground = true, widthDp = 360, heightDp = 740)
@Composable
fun ModernAppPreview() {
  // SpimViewModel is now an AndroidViewModel, so let the default factory build it
  SpimApp()
}
