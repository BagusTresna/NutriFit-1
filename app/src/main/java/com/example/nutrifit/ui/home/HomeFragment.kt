// HomeFragment.kt
package com.example.nutrifit.ui.home

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.nutrifit.R
import com.example.nutrifit.databinding.FragmentHomeBinding
import com.example.nutrifit.utils.TFLiteHelper
import org.apache.commons.csv.CSVFormat
import org.tensorflow.lite.Interpreter
import java.io.InputStreamReader
import java.nio.channels.FileChannel

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var tfliteInterpreter: Interpreter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadModel()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        TFLiteHelper.closeInterpreter()
    }

    override fun onResume() {
        super.onResume()
        setupDropdownMenus()
        setupButtonClickListener()
        testCsvReading()
    }

    private fun setupDropdownMenus() {
        val gender = resources.getStringArray(R.array.gender)
        binding.fieldGender.setAdapter(
            ArrayAdapter(requireContext(), R.layout.dropdown_item, gender)
        )

        val activity = resources.getStringArray(R.array.level)
        binding.fieldActivity.setAdapter(
            ArrayAdapter(requireContext(), R.layout.dropdown_item, activity)
        )

        val target = resources.getStringArray(R.array.target)
        binding.fieldTarget.setAdapter(
            ArrayAdapter(requireContext(), R.layout.dropdown_item, target)
        )
    }

    private fun setupButtonClickListener() {
        binding.btnSearch.setOnClickListener {
            processInputAndShowResults()
        }
    }

    private fun loadModel() {
        try {
            val assetManager = requireContext().assets
            val modelPath = "model.tflite"
            val fileDescriptor = assetManager.openFd(modelPath)
            val inputStream = fileDescriptor.createInputStream()
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength

            val buffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY, startOffset, declaredLength
            )
            tfliteInterpreter = Interpreter(buffer)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "Error loading model!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getRecipesFromCsv(): List<Map<String, String>> {
        return try {
            val csvInputStream = requireContext().assets.open("resep.csv")
            val csvParser = org.apache.commons.csv.CSVParser(
                InputStreamReader(csvInputStream),
                CSVFormat.DEFAULT.withHeader()
            )

            csvParser.map { record ->
                mapOf(
                    "nama_makanan" to record.get("nama_makanan"),
                    "kalori" to record.get("kalori"),
                    "jenis" to record.get("jenis"),
                    "image" to record.get("image"),
                    "cluster" to record.get("cluster") // Pastikan kolom cluster ada
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "Error reading CSV file!", Toast.LENGTH_SHORT).show()
            emptyList()
        }
    }


    private fun processInputAndShowResults() {
        val weight = binding.fieldWeight.text.toString().toFloatOrNull()
        val height = binding.fieldHeight.text.toString().toFloatOrNull()
        val age = binding.fieldAge.text.toString().toIntOrNull()

        val gender = when (binding.fieldGender.text.toString()) {
            "Male" -> 1f
            "Female" -> 0f
            else -> null
        }

        val activityLevel = when (binding.fieldActivity.text.toString()) {
            "InActive" -> 1.2f
            "Lightly Active" -> 1.375f
            "Moderately Active" -> 1.55f
            "Very Active" -> 1.725f
            "Extra Active" -> 1.9f
            else -> null
        }

        val target = binding.fieldTarget.text.toString().removeSuffix(" Kg").toFloatOrNull()

        if (weight == null || height == null || age == null || gender == null || activityLevel == null || target == null) {
            Toast.makeText(requireContext(), "Please fill in all fields correctly.", Toast.LENGTH_SHORT).show()
            return
        }

        val inputArray = arrayOf(floatArrayOf(weight, height, age.toFloat(), gender, activityLevel, target))
        Log.d("INPUT_DATA", "Input Array: ${inputArray.contentDeepToString()}")

        val output = TFLiteHelper.runInference(inputArray, tfliteInterpreter)

        if (output != null) {
            // Menampilkan hasil prediksi cluster
            Log.d("MODEL_OUTPUT", "Raw Output: ${output.contentDeepToString()}")

            val predictedCluster = output[0].withIndex().maxByOrNull { it.value }?.index ?: -1
            Log.d("PREDICTION", "Predicted Cluster: $predictedCluster")

            val recommendedRecipes = getRecipesFromCsv()

            if (recommendedRecipes.isEmpty()) {
                Toast.makeText(requireContext(), "Recipe data is empty.", Toast.LENGTH_SHORT).show()
                return
            }

            // Filter resep berdasarkan cluster
            val filteredRecipes = recommendedRecipes.filter {
                it["cluster"]?.toIntOrNull() == predictedCluster
            }

            if (filteredRecipes.isEmpty()) {
                Log.w("FILTERED_RECIPES", "No recipes found for cluster $predictedCluster.")
                Toast.makeText(requireContext(), "No recipes available for this cluster.", Toast.LENGTH_SHORT).show()
                return
            }

            // Menampilkan resep yang difilter ke logcat
            Log.d("FILTERED_RECIPES", "Filtered Recipes: $filteredRecipes")

            // Menampilkan resep per waktu makan
            val pagi = filteredRecipes.take(3)
            val siang = filteredRecipes.drop(3).take(3)
            val malam = filteredRecipes.drop(6).take(3)

            Log.d("RECOMMENDED_PAGI", pagi.joinToString(separator = "\n") { it.toString() })
            Log.d("RECOMMENDED_SIANG", siang.joinToString(separator = "\n") { it.toString() })
            Log.d("RECOMMENDED_MALAM", malam.joinToString(separator = "\n") { it.toString() })
        } else {
            Log.e("TFLITE_ERROR", "Output is null. Something went wrong.")
        }
    }


    private fun testCsvReading() {
        val recipes = getRecipesFromCsv()

        if (recipes.isEmpty()) {
            Log.e("CSV_TEST", "No data found in the CSV file.")
            return
        }

        recipes.forEachIndexed { index, recipe ->
            val message = "Recipe ${index + 1}: Name=${recipe["nama_makanan"]}, " +
                    "Calories=${recipe["kalori"]}, Type=${recipe["jenis"]}, " +
                    "Image=${recipe["image"]}"
            Log.d("CSV_TEST", message)
        }
    }
}
