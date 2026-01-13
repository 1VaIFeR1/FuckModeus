package com.fuck.modeus.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import com.fuck.modeus.R

class HelpDialogFragment : DialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.dialog_help, container, false)

        view.findViewById<View>(R.id.btnCloseHelp).setOnClickListener {
            dismiss()
        }

        return view
    }

    override fun onStart() {
        super.onStart()
        // Делаем окно широким и высоким (85-90% экрана)
        dialog?.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent) // Для закруглений
            val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
            val height = (resources.displayMetrics.heightPixels * 0.85).toInt()
            window.setLayout(width, height)
        }
    }
}