package org.example;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class SilpoDetailsParser {

    public static void main(String[] args) {
        // Беремо будь-яке посилання на ром
        String productUrl = "https://silpo.ua/product/rom-bacardi-carta-negra-40-8429";

        try {
            System.out.println("Підключаємось до: " + productUrl);

            // Завантажуємо HTML сторінки
            Document doc = Jsoup.connect(productUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                    .get();

            // 1. Знаходимо всі блоки з характеристиками за атрибутом data-autotestid
            Elements attributesBlocks = doc.select("div[data-autotestid=product-attributes-list-block]");

            System.out.println("--- Знайдені характеристики ---");

            // 2. Проходимося по кожному блоку
            for (Element block : attributesBlocks) {

                // Витягуємо назву (Країна, % спирту, Колір тощо)
                String label = block.select("[data-autotestid=product-attributes-list-block-title]").text().trim();

                // Витягуємо саме значення (Італія, 40, Black тощо)
                String value = block.select("[data-autotestid=product-attributes-list-block-value-text-right]").text().trim();

                // Виводимо в консоль
                System.out.println(label + " : " + value);

                // --- ТУТ МОЖЕШ ДОДАТИ СВОЮ ЛОГІКУ ЗБЕРЕЖЕННЯ ---
                // Наприклад:
                // if (label.equals("Країна")) { 
                //     myRumObject.setCountry(value); 
                // } else if (label.equals("% спирту")) { 
                //     myRumObject.setAlcohol(value); 
                // }
            }

            System.out.println("-------------------------------");

        } catch (Exception e) {
            System.err.println("Помилка парсингу: " + e.getMessage());
        }
    }
}