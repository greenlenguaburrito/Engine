package com.trucknav.pro.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.trucknav.pro.R
import com.trucknav.pro.data.TruckProfileStore
import com.trucknav.pro.model.TruckProfile

/** Modal for entering the truck's legal dimensions and hazmat status, matching the web prototype's "Truck Specs" sheet. */
class TruckProfileDialogFragment : DialogFragment() {

    var onProfileSaved: ((TruckProfile) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_truck_profile, null)
        val store = TruckProfileStore(requireContext())
        val current = store.load()

        val weightInput = view.findViewById<TextInputEditText>(R.id.weightInput)
        val heightInput = view.findViewById<TextInputEditText>(R.id.heightInput)
        val lengthInput = view.findViewById<TextInputEditText>(R.id.lengthInput)
        val hazmatSwitch = view.findViewById<SwitchMaterial>(R.id.hazmatSwitch)

        weightInput.setText(current.weightLbs.toString())
        heightInput.setText(current.heightFt.toString())
        lengthInput.setText(current.lengthFt.toString())
        hazmatSwitch.isChecked = current.hazmat

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .create()

        view.findViewById<android.view.View>(R.id.applyProfileButton).setOnClickListener {
            val profile = TruckProfile(
                weightLbs = weightInput.text.toString().toIntOrNull() ?: current.weightLbs,
                heightFt = heightInput.text.toString().toDoubleOrNull() ?: current.heightFt,
                lengthFt = lengthInput.text.toString().toDoubleOrNull() ?: current.lengthFt,
                hazmat = hazmatSwitch.isChecked
            )
            store.save(profile)
            onProfileSaved?.invoke(profile)
            dismiss()
        }

        return dialog
    }
}
